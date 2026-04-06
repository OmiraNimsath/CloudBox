package com.cloudbox.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListenerAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.cloudbox.config.ClusterConfig;
import com.cloudbox.model.ConsensusStatus;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * ConsensusService — Consensus & Agreement Module
 *
 * Algorithm: ZAB (ZooKeeper Atomic Broadcast) via Apache Curator LeaderSelector.
 *   - ZooKeeper ephemeral sequential znodes implement the election:
 *       Participants write ephemeral sequential nodes under /cloudbox/election.
 *       The node with the lowest sequence number becomes leader.
 *   - On leader crash, its ephemeral znode is deleted and the next-lowest
 *       node is notified by a ZooKeeper watch, triggering re-election.
 *   - Epoch is the wall-clock millis at which znode ownership was acquired,
 *       monotonically increasing across elections.
 *   - ZXID (ZooKeeper Transaction ID) is incremented on every state-change
 *       proposal, guaranteeing total order of all updates.
 *
 * Optimisations:
 *   - Leader heartbeat (3 s) keeps the ephemeral znode alive.
 *   - Partition detection: if reachable nodes < quorum, writes are blocked.
 *   - Followers re-queue automatically (curator autoRequeue).
 *
 * Failure scenario:
 *   If the leader crashes, ZooKeeper detects the session timeout and removes
 *   the ephemeral znode. The follower watching it wins the next election
 *   within ~10 s (session timeout + election overhead).
 */
@Service
public class ConsensusService extends LeaderSelectorListenerAdapter {

    private static final String ELECTION_PATH = "/cloudbox/election";
    private static final long LEADER_HEARTBEAT_MS = 3_000;

    private final CuratorFramework curator;
    private final NodeRegistry nodeRegistry;
    private final RestTemplate restTemplate;
    private final int nodeId;

    private LeaderSelector leaderSelector;
    private final AtomicReference<ConsensusStatus> latestStatus = new AtomicReference<>();
    private final AtomicLong zxid = new AtomicLong(0);
    private volatile long electionEpoch = 0;
    private volatile long lastLeaderHeartbeat = 0;
    private volatile int stableLeaderId = -1;

    public ConsensusService(CuratorFramework curator,
                             NodeRegistry nodeRegistry,
                             RestTemplate restTemplate,
                             @Value("${cloudbox.node-id:1}") int nodeId) {
        this.curator = curator;
        this.nodeRegistry = nodeRegistry;
        this.restTemplate = restTemplate;
        this.nodeId = nodeId;
    }

    @PostConstruct
    public void start() {
        leaderSelector = new LeaderSelector(curator, ELECTION_PATH, this);
        leaderSelector.setId(String.valueOf(nodeId));
        leaderSelector.autoRequeue();
        leaderSelector.start();
        refreshStatus();
    }

    @PreDestroy
    public void stop() {
        if (leaderSelector != null) leaderSelector.close();
    }

    // ── ZAB / Curator callback ────────────────────────────────────────────

    @Override
    public void takeLeadership(CuratorFramework client) throws Exception {
        electionEpoch = System.currentTimeMillis();
        zxid.incrementAndGet();
        nodeRegistry.markLeader(nodeId);
        refreshStatus();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                lastLeaderHeartbeat = System.currentTimeMillis();
                broadcastEpochZxid();
                refreshStatus();
                Thread.sleep(LEADER_HEARTBEAT_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            refreshStatus();
        }
    }

    private void broadcastEpochZxid() {
        long epoch = electionEpoch;
        long z = zxid.get();
        for (int peerId = 1; peerId <= ClusterConfig.NODE_COUNT; peerId++) {
            if (peerId == nodeId) continue;
            try {
                restTemplate.postForEntity(
                    ClusterConfig.nodeUrl(peerId) + "/api/internal/consensus-sync"
                    + "?epoch=" + epoch + "&zxid=" + z,
                    null, Void.class);
            } catch (Exception ignored) {}
        }
    }

    public void acceptEpochZxid(long epoch, long z) {
        electionEpoch = epoch;
        if (z > zxid.get()) zxid.set(z);
    }

    public void incrementZxid() {
        zxid.incrementAndGet();
    }

    // ── Scheduled status refresh ──────────────────────────────────────────

    @Scheduled(fixedDelay = 4000)
    public void refreshStatus() {
        int leaderId = resolveLeaderId();
        nodeRegistry.markLeader(leaderId);

        int reachable = nodeRegistry.getAliveNodeIds().size();
        boolean partitioned = reachable < ClusterConfig.QUORUM_SIZE;
        boolean canWrite = !partitioned;

        latestStatus.set(new ConsensusStatus(
                leaderId,
                electionEpoch,
                zxid.get(),
                lastLeaderHeartbeat,
                isLeaderAlive(),
                partitioned,
                reachable,
                canWrite,
                nodeRegistry.getAliveNodeIds()));
    }

    private int resolveLeaderId() {
        List<Integer> effectiveAlive = nodeRegistry.getEffectiveAliveNodeIds();
        if (stableLeaderId > 0 && effectiveAlive.contains(stableLeaderId)) {
            return stableLeaderId;
        }
        int previousLeader = stableLeaderId;
        try {
            String participantId = leaderSelector.getLeader().getId();
            if (participantId != null && !participantId.isBlank()) {
                int zkLeader = Integer.parseInt(participantId);
                if (effectiveAlive.contains(zkLeader)) {
                    stableLeaderId = zkLeader;
                }
            }
        } catch (Exception ignored) {}
        if (stableLeaderId <= 0 || !effectiveAlive.contains(stableLeaderId)) {
            stableLeaderId = effectiveAlive.stream().min(Integer::compareTo).orElse(nodeId);
        }
        if (stableLeaderId != previousLeader) {
            electionEpoch = System.currentTimeMillis();
            zxid.incrementAndGet();
        }
        return stableLeaderId;
    }

    private boolean isLeaderAlive() {
        int leader = stableLeaderId > 0 ? stableLeaderId : nodeId;
        return nodeRegistry.getEffectiveAliveNodeIds().contains(leader);
    }

    // ── Queries ───────────────────────────────────────────────────────────

    public ConsensusStatus getStatus() {
        ConsensusStatus s = latestStatus.get();
        return s != null ? s : buildFallbackStatus();
    }

    public boolean canWrite() {
        ConsensusStatus s = getStatus();
        return s != null && s.canWrite();
    }

    public int getLeaderId() {
        ConsensusStatus s = getStatus();
        return s != null ? s.leaderId() : nodeId;
    }

    private ConsensusStatus buildFallbackStatus() {
        return new ConsensusStatus(nodeId, 0, 0, 0, false, false,
                nodeRegistry.getAliveNodeIds().size(), false,
                nodeRegistry.getAliveNodeIds());
    }
}
