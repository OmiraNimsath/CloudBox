package com.cloudbox.service;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.cloudbox.model.ClusterStatus;
import com.cloudbox.model.LeaderInfo;
import com.cloudbox.model.PartitionStatus;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * ClusterCoordinator orchestrates all consensus components.
 *
 * Responsibilities:
 * - Start/stop leader election, partition detection, and consensus manager
 * - Provide unified view of cluster state
 * - Coordinate between different consensus modules
 */
@Slf4j
@Service
public class ClusterCoordinator {

    @Autowired
    private LeaderElectionService leaderElectionService;

    @Autowired
    private PartitionHandler partitionHandler;

    @Autowired
    private ConsensusManager consensusManager;

    @Value("${cloudbox.node-id:1}")
    private int nodeId;

    private volatile boolean initialized = false;

    /**
     * Start all consensus components and initialize cluster coordination.
     */
    public void startClusterCoordination() {
        if (initialized) {
            log.warn("Cluster coordination already started");
            return;
        }

        try {
            log.info("Starting cluster coordination on node {}", nodeId);

            // Start in order of dependency
            leaderElectionService.startLeaderElection();
            partitionHandler.startPartitionDetection();
            consensusManager.initialize();

            initialized = true;
            log.info("Cluster coordination started successfully");
        } catch (Exception e) {
            log.error("Failed to start cluster coordination", e);
            throw new RuntimeException("Cluster coordination startup failed", e);
        }
    }

    /**
     * Stop all consensus components and clean up resources.
     */
    public void stopClusterCoordination() {
        if (!initialized) {
            log.warn("Cluster coordination not started");
            return;
        }

        try {
            log.info("Stopping cluster coordination on node {}", nodeId);

            // Stop in reverse order of startup
            partitionHandler.stopPartitionDetection();
            leaderElectionService.stopLeaderElection();

            initialized = false;
            log.info("Cluster coordination stopped");
        } catch (Exception e) {
            log.error("Error stopping cluster coordination", e);
        }
    }

    /**
     * Get complete cluster status snapshot.
     *
     * Returns aggregated state from all consensus components.
     */
    public ClusterStatus getClusterStatus() {
        LeaderInfo leader = leaderElectionService.getCurrentLeader();
        PartitionStatus partition = partitionHandler.getPartitionStatus();

        // Build node statuses map
        Map<Integer, String> nodeStatuses = new HashMap<>();
        if (partition.getResponseNodes() != null) {
            for (int i = 1; i <= 5; i++) {
                if (partition.getResponseNodes().contains(i)) {
                    nodeStatuses.put(i, "HEALTHY");
                } else {
                    nodeStatuses.put(i, "UNREACHABLE");
                }
            }
        }

        return ClusterStatus.builder()
                .clusterHealthy(!partition.isPartitioned())
                .activeNodes(partition.getResponseNodes())
                .leader(leader)
                .partitionStatus(partition)
                .timestamp(System.currentTimeMillis())
                .nodeStatuses(nodeStatuses)
                .build();
    }

    /**
     * Check if this node can perform writes.
     */
    public boolean canWrite() {
        return partitionHandler.canWrite();
    }

    /**
     * Check if this node is the current leader.
     */
    public boolean isLeader() {
        return leaderElectionService.isCurrentLeader();
    }

    /**
     * Get current leader information.
     */
    public LeaderInfo getLeaderInfo() {
        return leaderElectionService.getCurrentLeader();
    }

    /**
     * Get partition status.
     */
    public PartitionStatus getPartitionStatus() {
        return partitionHandler.getPartitionStatus();
    }

    /**
     * Check if consensus coordinator is initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Get node ID of this instance.
     */
    public int getNodeId() {
        return nodeId;
    }

    /**
     * Propose a value for atomic broadcast.
     * Delegates to ConsensusManager.
     */
    public com.cloudbox.model.ConsensusProposal propose(String data) throws Exception {
        return consensusManager.propose(data);
    }

    /**
     * Cleanup on application shutdown.
     * Gracefully stops cluster coordination.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down cluster coordination on application shutdown");
        stopClusterCoordination();
    }
}
