package com.cloudbox.service;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.cloudbox.config.ClusterConfig;
import com.cloudbox.model.PartitionStatus;

import lombok.extern.slf4j.Slf4j;

/**
 * PartitionHandler detects network partitions and manages cluster write availability.
 *
 * Responsibilities:
 * - Detect network partitions (split-brain scenarios)
 * - Ensure minority partition stops accepting writes
 * - Recover and reconcile after partition heals
 * - Prevent inconsistent state during partition
 */
@Slf4j
@Service
public class PartitionHandler {

    @Autowired(required = false)
    private RestTemplate restTemplate;

    @Value("${cloudbox.node-id:1}")
    private int nodeId;

    private AtomicReference<PartitionStatus> partitionStatus = new AtomicReference<>();
    private ScheduledExecutorService partitionChecker;
    private final long PARTITION_CHECK_INTERVAL = 5000; // 5 seconds

    /**
     * Initialize partition detection.
     */
    public void startPartitionDetection() {
        log.info("Starting partition detection on node {}", nodeId);

        partitionChecker = Executors.newScheduledThreadPool(1);
        partitionChecker.scheduleAtFixedRate(
                this::detectPartition,
                PARTITION_CHECK_INTERVAL,
                PARTITION_CHECK_INTERVAL,
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Stop partition detection and clean up resources.
     */
    public void stopPartitionDetection() {
        if (partitionChecker != null) {
            partitionChecker.shutdown();
            try {
                if (!partitionChecker.awaitTermination(5, TimeUnit.SECONDS)) {
                    partitionChecker.shutdownNow();
                }
            } catch (InterruptedException e) {
                partitionChecker.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("Partition detection stopped on node {}", nodeId);
    }

    /**
     * Detect network partition by checking reachable nodes.
     *
     * A partition is detected when fewer than QUORUM_SIZE (3) nodes are reachable.
     * If partitioned, writes must be blocked to prevent divergent state.
     */
    private void detectPartition() {
        try {
            Set<Integer> reachableNodes = new HashSet<>();
            reachableNodes.add(nodeId); // This node is always reachable to itself

            // Try to reach all other nodes
            for (int i = 1; i <= ClusterConfig.NODE_COUNT; i++) {
                if (i == nodeId) continue;

                if (isNodeReachable(i)) {
                    reachableNodes.add(i);
                }
            }

            int reachableCount = reachableNodes.size();
            boolean canWrite = reachableCount >= ClusterConfig.QUORUM_SIZE;
            boolean isPartitioned = reachableCount < ClusterConfig.QUORUM_SIZE;

            PartitionStatus status = PartitionStatus.builder()
                    .partitioned(isPartitioned)
                    .reachableNodes(reachableCount)
                    .canWrite(canWrite)
                    .responseNodes(reachableNodes)
                    .detectionTime(System.currentTimeMillis())
                    .partitionDescription(buildPartitionDescription(isPartitioned, reachableCount))
                    .build();

            PartitionStatus previousStatus = partitionStatus.getAndSet(status);

            // Log partition state changes
            if (previousStatus == null || previousStatus.isPartitioned() != isPartitioned) {
                if (isPartitioned) {
                    log.warn("PARTITION DETECTED: Only {} nodes reachable (need {}). BLOCKING WRITES.",
                            reachableCount, ClusterConfig.QUORUM_SIZE);
                } else {
                    log.info("Partition healed. All {} nodes reachable. Writes allowed.", reachableCount);
                }
            }

        } catch (Exception e) {
            log.error("Error detecting partition", e);
        }
    }

    /**
     * Check if a remote node is reachable via HTTP health check.
     */
    private boolean isNodeReachable(int remoteNodeId) {
        try {
            String nodeUrl = ClusterConfig.getNodeUrl(remoteNodeId) + "/api/health";
            
            if (restTemplate == null) {
                // Fallback: assume all nodes are reachable if RestTemplate not available
                return true;
            }

            restTemplate.getForObject(nodeUrl, String.class);
            return true;
        } catch (Exception e) {
            log.debug("Node {} unreachable: {}", remoteNodeId, e.getMessage());
            return false;
        }
    }

    /**
     * Build human-readable partition description.
     */
    private String buildPartitionDescription(boolean isPartitioned, int reachableCount) {
        if (isPartitioned) {
            return String.format("Partition: %d/%d nodes reachable (minority partition - writes blocked)",
                    reachableCount, ClusterConfig.NODE_COUNT);
        } else {
            return String.format("Healthy: %d/%d nodes reachable (writes allowed)",
                    reachableCount, ClusterConfig.NODE_COUNT);
        }
    }

    /**
     * Get current partition status.
     */
    public PartitionStatus getPartitionStatus() {
        PartitionStatus status = partitionStatus.get();
        if (status == null) {
            // Initialize if not yet checked
            status = PartitionStatus.builder()
                    .partitioned(false)
                    .reachableNodes(ClusterConfig.NODE_COUNT)
                    .canWrite(true)
                    .detectionTime(System.currentTimeMillis())
                    .partitionDescription("Not yet initialized")
                    .build();
        }
        return status;
    }

    /**
     * Check if writes are allowed based on partition status.
     */
    public boolean canWrite() {
        PartitionStatus status = getPartitionStatus();
        return status.isCanWrite();
    }

    /**
     * Get count of reachable nodes.
     */
    public int getReachableNodeCount() {
        PartitionStatus status = getPartitionStatus();
        return status.getReachableNodes();
    }

    /**
     * Check if cluster is partitioned.
     */
    public boolean isPartitioned() {
        PartitionStatus status = getPartitionStatus();
        return status.isPartitioned();
    }
}
