package com.cloudbox.service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.CreateMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.cloudbox.config.ClusterConfig;
import com.cloudbox.model.ConsensusProposal;

import lombok.extern.slf4j.Slf4j;

/**
 * ConsensusManager handles consensus algorithm implementation using ZooKeeper's ZAB protocol.
 *
 * Responsibilities:
 * - Leverage ZooKeeper's ZAB protocol for atomic broadcasts
 * - Ensure all nodes receive updates in same order
 * - Implement proposal and acceptance mechanism
 * - Generate ZXIDs (ZooKeeper transaction IDs) for ordering
 */
@Slf4j
@Service
public class ConsensusManager {

    @Autowired
    private CuratorFramework curatorFramework;

    @Autowired
    private LeaderElectionService leaderElectionService;

    @Autowired
    private PartitionHandler partitionHandler;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${cloudbox.node-id:1}")
    private int nodeId;

    private AtomicLong zxidCounter = new AtomicLong(0);
    private long currentEpoch = 0;

    /**
     * Initialize consensus manager.
     */
    public void initialize() {
        log.info("Initializing ConsensusManager on node {}", nodeId);

        // Initialize ZXID with current epoch and timestamp
        long epoch = leaderElectionService.getCurrentElectionEpoch();
        if (epoch > 0) {
            currentEpoch = epoch;
        }
    }

    /**
     * Propose a value for atomic broadcast (ZAB protocol).
     *
     * Only the leader can propose. Followers send proposals to the leader.
     * The proposal will be broadcast to all followers in order.
     *
     * @param data The data/operation to broadcast
     * @return ConsensusProposal representing the proposed transaction
     * @throws IllegalStateException if not leader and partition prevents reaching leader
     */
    public ConsensusProposal propose(String data) throws Exception {
        // Check if we have quorum (can write)
        if (!partitionHandler.canWrite()) {
            throw new IllegalStateException("Cannot propose: no quorum available");
        }

        // Generate new ZXID
        long newZxid = generateZxid();
        String proposalId = String.format("%d-%d", currentEpoch, newZxid);

        ConsensusProposal proposal = ConsensusProposal.builder()
                .proposalId(proposalId)
                .epoch(currentEpoch)
                .zxid(newZxid)
                .data(data)
                .proposerId(nodeId)
                .timestamp(System.currentTimeMillis())
                .build();

        log.debug("Proposing transaction {}: {}", proposalId, data);

        // If this node is the leader, write to ZooKeeper's ZAB log
        if (leaderElectionService.isCurrentLeader()) {
            broadcastProposal(proposal);
        } else {
            // Followers forward proposal to leader
            forwardToLeader(proposal);
        }

        return proposal;
    }

    /**
     * Broadcast a proposal using ZooKeeper's atomic broadcast.
     *
     * Creates an ephemeral sequential node representing the proposal.
     * All followers watch this path and receive the proposal atomically.
     */
    private void broadcastProposal(ConsensusProposal proposal) throws Exception {
        try {
            String proposalPath = ClusterConfig.ZK_NAMESPACE + "/proposals/" + proposal.getProposalId();
            String proposalData = serializeProposal(proposal);

            // Create with EPHEMERAL_SEQUENTIAL to ensure ordering
            curatorFramework.create()
                    .creatingParentsIfNeeded()
                    .withMode(CreateMode.PERSISTENT_SEQUENTIAL)
                    .forPath(proposalPath, proposalData.getBytes());

            log.debug("Broadcasted proposal {} to all nodes", proposal.getProposalId());
        } catch (Exception e) {
            log.error("Failed to broadcast proposal", e);
            throw e;
        }
    }

    /**
     * Forward proposal to the leader via HTTP POST.
     *
     * In ZAB, only the leader may broadcast. Followers must send their
     * proposals to the leader, which then atomically broadcasts them.
     */
    private void forwardToLeader(ConsensusProposal proposal) throws Exception {
        int leaderId = leaderElectionService.getCurrentLeader() != null
                ? leaderElectionService.getCurrentLeader().getLeaderId()
                : -1;

        if (leaderId < 1) {
            throw new IllegalStateException("No leader available to forward proposal");
        }

        String leaderUrl = ClusterConfig.getNodeUrl(leaderId) + "/api/cluster/consensus/propose";
        log.debug("Node {} forwarding proposal {} to leader {} at {}",
                nodeId, proposal.getProposalId(), leaderId, leaderUrl);

        restTemplate.postForEntity(leaderUrl, Map.of("data", proposal.getData()), Object.class);
    }

    /**
     * Generate a unique ZXID (ZooKeeper transaction ID).
     *
     * Format: (epoch << 32) | counter
     * - epoch: 32-bit election epoch (ensures ordering across leader changes)
     * - counter: 32-bit per-epoch counter
     */
    private long generateZxid() {
        long epoch = leaderElectionService.getCurrentElectionEpoch();
        if (epoch < 0) {
            epoch = currentEpoch;
        } else {
            currentEpoch = epoch;
        }

        long counter = zxidCounter.incrementAndGet();
        return (epoch << 32) | (counter & 0xFFFFFFFFL);
    }

    /**
     * Serialize proposal to JSON format.
     */
    private String serializeProposal(ConsensusProposal proposal) {
        // Simple serialization; could use Jackson JSON in production
        return String.format("{\"id\":\"%s\",\"epoch\":%d,\"zxid\":%d,\"data\":\"%s\",\"proposer\":%d,\"ts\":%d}",
                proposal.getProposalId(), proposal.getEpoch(), proposal.getZxid(),
                proposal.getData().replace("\"", "\\\""), proposal.getProposerId(),
                proposal.getTimestamp());
    }

    /**
     * Get current consensus epoch.
     */
    public long getCurrentEpoch() {
        long electionEpoch = leaderElectionService.getCurrentElectionEpoch();
        return electionEpoch > 0 ? electionEpoch : currentEpoch;
    }

    /**
     * Get current ZXID counter.
     */
    public long getCurrentZxidCounter() {
        return zxidCounter.get();
    }

    /**
     * Check if can propose (has quorum).
     */
    public boolean canPropose() {
        return partitionHandler.canWrite();
    }
}
