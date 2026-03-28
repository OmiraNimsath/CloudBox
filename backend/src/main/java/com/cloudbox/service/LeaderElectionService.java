package com.cloudbox.service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListenerAdapter;
import org.apache.zookeeper.CreateMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.cloudbox.config.ClusterConfig;
import com.cloudbox.model.LeaderInfo;

import lombok.extern.slf4j.Slf4j;

/**
 * LeaderElectionService handles leader election using ZooKeeper's capabilities.
 *
 * Responsibilities:
 * - Use ZooKeeper ephemeral sequential nodes for leader election
 * - Implement leader heartbeat mechanism (3-second intervals)
 * - Detect leader crashes and trigger re-election
 * - Ensure only one leader at a time
 * - Provide callbacks for leadership changes
 */
@Slf4j
@Service
public class LeaderElectionService extends LeaderSelectorListenerAdapter {

    @Autowired
    private CuratorFramework curatorFramework;

    @Value("${cloudbox.node-id:1}")
    private int nodeId;

    @Value("${cloudbox.leader-election-timeout:10000}")
    private long leaderElectionTimeout;

    @Value("${cloudbox.heartbeat-interval:3000}")
    private long heartbeatInterval;

    private LeaderSelector leaderSelector;
    private AtomicReference<LeaderInfo> currentLeader = new AtomicReference<>();
    private volatile boolean isLeader = false;
    private ScheduledExecutorService heartbeatExecutor;

    /**
     * Initialize leader election when service starts.
     */
    public void startLeaderElection() {
        try {
            log.info("Starting leader election for node {}", nodeId);

            // Create LeaderSelector which will automatically participate in election
            leaderSelector = new LeaderSelector(curatorFramework, ClusterConfig.ZK_ELECTION_PATH, this);
            leaderSelector.autoRequeue();
            leaderSelector.start();

            // Initialize heartbeat executor
            heartbeatExecutor = Executors.newScheduledThreadPool(1);

            log.info("Leader election initialized");
        } catch (Exception e) {
            log.error("Failed to start leader election", e);
            throw new RuntimeException("Leader election initialization failed", e);
        }
    }

    /**
     * Stop leader election and clean up resources.
     */
    public void stopLeaderElection() {
        try {
            if (leaderSelector != null) {
                leaderSelector.close();
            }
            if (heartbeatExecutor != null) {
                heartbeatExecutor.shutdown();
                if (!heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    heartbeatExecutor.shutdownNow();
                }
            }
            log.info("Leader election stopped");
        } catch (Exception e) {
            log.error("Error stopping leader election", e);
        }
    }

    /**
     * Callback when this node becomes the leader (called by LeaderSelector).
     */
    @Override
    public void takeLeadership(CuratorFramework client) throws Exception {
        isLeader = true;
        long electionEpoch = System.currentTimeMillis();

        try {
            log.info("Node {} became leader (epoch: {})", nodeId, electionEpoch);

            // Update internal leader info
            currentLeader.set(LeaderInfo.builder()
                    .leaderId(nodeId)
                    .electionEpoch(electionEpoch)
                    .zxid(curatorFramework.getZookeeperClient().getZooKeeper().getSessionId())
                    .lastHeartbeat(System.currentTimeMillis())
                    .alive(true)
                    .build());

            // Write leader info to ZooKeeper
            updateLeaderMetadata(client, nodeId);

            // Start heartbeat mechanism
            startHeartbeat();

            // Block until leadership is lost or thread is interrupted
            Thread.sleep(Long.MAX_VALUE);

        } catch (InterruptedException e) {
            log.info("Leadership interrupted for node {}", nodeId);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Error during leadership", e);
            throw e;
        } finally {
            isLeader = false;
            if (heartbeatExecutor != null && !heartbeatExecutor.isShutdown()) {
                heartbeatExecutor.shutdownNow();
            }
            log.info("Node {} lost leadership", nodeId);
        }
    }

    /**
     * Start sending heartbeats to keep leadership alive.
     */
    private void startHeartbeat() {
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                if (isLeader) {
                    LeaderInfo leader = currentLeader.get();
                    if (leader != null) {
                        leader.setLastHeartbeat(System.currentTimeMillis());
                        updateLeaderMetadata(curatorFramework, nodeId);
                    }
                }
            } catch (Exception e) {
                log.warn("Error sending heartbeat", e);
            }
        }, heartbeatInterval, heartbeatInterval, TimeUnit.MILLISECONDS);
    }

    /**
     * Update leader metadata in ZooKeeper.
     */
    private void updateLeaderMetadata(CuratorFramework client, int leaderId) throws Exception {
        String leaderPath = ClusterConfig.ZK_ELECTION_PATH + "/leader";
        long epoch = currentLeader.get() != null ? currentLeader.get().getElectionEpoch() : System.currentTimeMillis();
        String leaderData = String.format("%d:%d:%d:%d", leaderId, System.currentTimeMillis(),
                curatorFramework.getZookeeperClient().getZooKeeper().getSessionId(), epoch);

        try {
            if (client.checkExists().forPath(leaderPath) != null) {
                client.setData().forPath(leaderPath, leaderData.getBytes());
            } else {
                client.create().creatingParentsIfNeeded()
                        .withMode(CreateMode.EPHEMERAL)
                        .forPath(leaderPath, leaderData.getBytes());
            }
        } catch (Exception e) {
            log.warn("Could not update leader metadata", e);
        }
    }

    /**
     * Get current leader information.
     */
    public LeaderInfo getCurrentLeader() {
        if (isLeader) {
            return currentLeader.get();
        }
        
        try {
            String leaderPath = ClusterConfig.ZK_ELECTION_PATH + "/leader";
            if (curatorFramework.checkExists().forPath(leaderPath) != null) {
                byte[] data = curatorFramework.getData().forPath(leaderPath);
                if (data != null && data.length > 0) {
                    String[] parts = new String(data).split(":");
                    if (parts.length >= 3) {
                        int id = Integer.parseInt(parts[0]);
                        long hb = Long.parseLong(parts[1]);
                        long zx = Long.parseLong(parts[2]);
                        long epoch = parts.length > 3 ? Long.parseLong(parts[3]) : 0;
                        
                        return LeaderInfo.builder()
                                .leaderId(id)
                                .electionEpoch(epoch)
                                .zxid(zx)
                                .lastHeartbeat(hb)
                                .alive(System.currentTimeMillis() - hb < leaderElectionTimeout)
                                .build();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error fetching leader info from ZooKeeper", e);
        }
        
        return null;
    }

    /**
     * Check if this node is the current leader.
     */
    public boolean isCurrentLeader() {
        return isLeader;
    }

    /**
     * Get current election epoch.
     */
    public long getCurrentElectionEpoch() {
        LeaderInfo leader = currentLeader.get();
        return leader != null ? leader.getElectionEpoch() : -1;
    }
}
