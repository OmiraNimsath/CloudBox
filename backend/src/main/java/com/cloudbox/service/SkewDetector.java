package com.cloudbox.service;

import com.cloudbox.config.TimeSyncProperties;
import com.cloudbox.model.ClockSkewInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Service for detecting and tracking clock skew between nodes.
 *
 * Periodically measures time differences between this node and remote nodes,
 * detects anomalies, and alerts if skew exceeds configured threshold.
 */
@Slf4j
@Service
public class SkewDetector {

    private final TimeSyncProperties timeSyncProperties;
    private final RestTemplate restTemplate;
    private final int nodeId;
    private final int totalNodes;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.cloudbox.controller.HealthController healthController;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private FailureDetectionService failureDetectionService;

    private final ReentrantReadWriteLock skewLock = new ReentrantReadWriteLock();
    private final Map<Integer, ClockSkewInfo> skewMap = new HashMap<>();
    private long maxClockSkew = 0;
    private boolean alertActive = false;

    public SkewDetector(
            TimeSyncProperties timeSyncProperties,
            RestTemplate restTemplate,
            @Value("${cloudbox.node-id:1}") int nodeId,
            @Value("${cloudbox.cluster-size:5}") int totalNodes) {
        this.timeSyncProperties = timeSyncProperties;
        this.restTemplate = restTemplate;
        this.nodeId = nodeId;
        this.totalNodes = totalNodes;

        log.info("SkewDetector initialized for node {} in cluster of {} nodes",
                nodeId, totalNodes);
    }

    /**
     * Record skew measurement for a remote node.
     *
     * @param remoteNodeId The remote node ID
     * @param skewMillis   Measured skew in milliseconds (positive = remote ahead, negative = remote behind)
     */
    public void recordSkew(int remoteNodeId, long skewMillis) {
        skewLock.writeLock().lock();
        try {
            ClockSkewInfo skewInfo = skewMap.getOrDefault(remoteNodeId,
                    ClockSkewInfo.builder()
                            .nodeId(remoteNodeId)
                            .build());

            skewInfo.setSkewMillis(skewMillis);
            skewInfo.setLastMeasuredAt(System.currentTimeMillis());

            // Update maximum skew
            long absSkew = Math.abs(skewMillis);
            if (absSkew > Math.abs(skewInfo.getMaxSkewMillis())) {
                skewInfo.setMaxSkewMillis(skewMillis);
            }

            // Check threshold
            long threshold = timeSyncProperties.getClock_skew_threshold_ms();
            boolean exceeds = absSkew > threshold;

            if (exceeds && !skewInfo.isAlertTriggered()) {
                skewInfo.setAlertTriggered(true);
                alertActive = true;
                log.warn("Clock skew alert for node {}: skew={}ms (threshold={}ms)",
                        remoteNodeId, skewMillis, threshold);
            } else if (!exceeds && skewInfo.isAlertTriggered()) {
                skewInfo.setAlertTriggered(false);
                log.info("Clock skew normalized for node {}: skew={}ms (threshold={}ms)",
                        remoteNodeId, skewMillis, threshold);
            }

            skewMap.put(remoteNodeId, skewInfo);

            // Update global max skew (historical peak — never decreases)
            long currentMax = skewMap.values().stream()
                    .mapToLong(info -> Math.abs(info.getMaxSkewMillis()))
                    .max()
                    .orElse(0);
            if (currentMax > maxClockSkew) {
                maxClockSkew = currentMax;
            }

            // Recompute global alert from all nodes (fixes alertActive never resetting)
            alertActive = skewMap.values().stream()
                    .anyMatch(ClockSkewInfo::isAlertTriggered);

        } finally {
            skewLock.writeLock().unlock();
        }
    }

    /**
     * Get skew information for a specific remote node.
     */
    public ClockSkewInfo getSkewInfo(int remoteNodeId) {
        skewLock.readLock().lock();
        try {
            ClockSkewInfo info = skewMap.get(remoteNodeId);
            if (info == null) {
                return ClockSkewInfo.builder()
                        .nodeId(remoteNodeId)
                        .skewMillis(0)
                        .build();
            }
            return info;
        } finally {
            skewLock.readLock().unlock();
        }
    }

    /**
     * Get all recorded skew information for all nodes.
     */
    public Map<Integer, ClockSkewInfo> getAllSkewInfo() {
        skewLock.readLock().lock();
        try {
            return new HashMap<>(skewMap);
        } finally {
            skewLock.readLock().unlock();
        }
    }

    /**
     * Get maximum observed clock skew in the cluster.
     */
    public long getMaxClockSkew() {
        skewLock.readLock().lock();
        try {
            return maxClockSkew;
        } finally {
            skewLock.readLock().unlock();
        }
    }

    /**
     * Check if any node exceeds the skew threshold.
     */
    public boolean isAlertActive() {
        skewLock.readLock().lock();
        try {
            return alertActive;
        } finally {
            skewLock.readLock().unlock();
        }
    }

    /**
     * Get count of nodes with alert status.
     */
    public int getAlertNodeCount() {
        skewLock.readLock().lock();
        try {
            return (int) skewMap.values().stream()
                    .filter(ClockSkewInfo::isAlertTriggered)
                    .count();
        } finally {
            skewLock.readLock().unlock();
        }
    }

    /**
     * Periodic skew detection task (scheduled).
     * Polls remote nodes to measure time differences.
     */
    @Scheduled(
            initialDelay = 10000, // Wait 10s after startup
            fixedDelayString = "${cloudbox.timesync.skew-check-interval}",
            timeUnit = java.util.concurrent.TimeUnit.MILLISECONDS
    )
    public void detectSkew() {
        if (healthController != null && healthController.isSimulatingFailure()) {
            return;
        }
        try {
            List<Integer> remoteNodeIds = getRemoteNodeIds();
            for (int remoteNodeId : remoteNodeIds) {
                measureSkewWithNode(remoteNodeId);
            }
        } catch (Exception e) {
            log.warn("Skew detection cycle failed", e);
        }
    }

    /**
     * Measure skew with a specific remote node using Cristian's RTT formula.
     * Fetches remote node's current time and corrects for network round-trip.
     * Skips nodes already known to be unhealthy to avoid wasting calls and
     * leaving stale skew entries in the map.
     */
    private void measureSkewWithNode(int remoteNodeId) {
        // Skip nodes that are already known failed — stale pings produce noise
        if (failureDetectionService != null
                && failureDetectionService.isNodeUnhealthy("node-" + remoteNodeId)) {
            return;
        }
        try {
            String remoteUrl = String.format("http://localhost:%d/api/timesync/time",
                    8080 + remoteNodeId - 1);

            long t0 = System.currentTimeMillis();
            Long remoteTime = restTemplate.getForObject(remoteUrl, Long.class);
            long t1 = System.currentTimeMillis();

            if (remoteTime != null) {
                long rtt = t1 - t0;
                // Cristian: estimate remote time at the moment we received the response
                long estimatedRemoteNow = remoteTime + rtt / 2;
                long skew = estimatedRemoteNow - t1; // positive = remote ahead

                recordSkew(remoteNodeId, skew);

                log.debug("Skew measurement node {} -> node {}: {}ms (RTT={}ms)",
                        nodeId, remoteNodeId, skew, rtt);
            }
        } catch (RuntimeException e) {
            log.debug("Failed to measure skew with node {}: {}",
                    remoteNodeId, e.getMessage());
        }
    }

    /**
     * Get list of remote node IDs (all nodes except this one).
     */
    private List<Integer> getRemoteNodeIds() {
        List<Integer> remoteIds = new ArrayList<>();
        for (int i = 1; i <= totalNodes; i++) {
            if (i != nodeId) {
                remoteIds.add(i);
            }
        }
        return remoteIds;
    }

    /**
     * Get synchronization status - count of nodes within acceptable skew.
     * Includes self (this node always has 0 skew with itself).
     * Excludes nodes that are unhealthy/failed even if their last skew reading was fine.
     */
    public int getInSyncNodeCount() {
        skewLock.readLock().lock();
        try {
            long threshold = timeSyncProperties.getClock_skew_threshold_ms();
            int remoteInSync = (int) skewMap.values().stream()
                    .filter(info -> Math.abs(info.getSkewMillis()) <= threshold)
                    .filter(info -> {
                        // Exclude nodes that are unhealthy — stale skew readings are misleading
                        if (failureDetectionService == null) return true;
                        return !failureDetectionService.isNodeUnhealthy("node-" + info.getNodeId());
                    })
                    .count();
            return remoteInSync + 1; // +1 for self (always in sync with itself)
        } finally {
            skewLock.readLock().unlock();
        }
    }

    /**
     * Clear all skew measurements (useful for testing/resets).
     */
    public void clearSkewData() {
        skewLock.writeLock().lock();
        try {
            skewMap.clear();
            maxClockSkew = 0;
            alertActive = false;
            log.info("Skew data cleared for node {}", nodeId);
        } finally {
            skewLock.writeLock().unlock();
        }
    }

    /**
     * Get comprehensive skew report for monitoring/debugging.
     * Includes self-node with skew=0 so the table always shows all 5 nodes.
     */
    public SkewReport generateReport() {
        skewLock.readLock().lock();
        try {
            // Deep-copy entries so we can set nodeStatus without touching shared skewMap objects
            List<ClockSkewInfo> skewList = new ArrayList<>();
            for (ClockSkewInfo info : skewMap.values()) {
                skewList.add(ClockSkewInfo.builder()
                        .nodeId(info.getNodeId())
                        .skewMillis(info.getSkewMillis())
                        .maxSkewMillis(info.getMaxSkewMillis())
                        .alertTriggered(info.isAlertTriggered())
                        .lastMeasuredAt(info.getLastMeasuredAt())
                        .build());
            }

            // Ensure ALL cluster nodes appear; add placeholders for nodes never measured
            for (int i = 1; i <= totalNodes; i++) {
                final int id = i;
                boolean exists = skewList.stream().anyMatch(s -> s.getNodeId() == id);
                if (!exists) {
                    skewList.add(ClockSkewInfo.builder()
                            .nodeId(id)
                            .skewMillis(0)
                            .maxSkewMillis(0)
                            .alertTriggered(false)
                            .lastMeasuredAt(0)
                            .nodeStatus(id == nodeId ? "HEALTHY" : "UNREACHABLE")
                            .build());
                }
            }

            // Enrich each entry with live failure status from FailureDetectionService
            for (ClockSkewInfo info : skewList) {
                if (info.getNodeStatus() != null) continue; // already set for placeholders
                if (info.getNodeId() == nodeId) {
                    info.setNodeStatus("HEALTHY");
                } else if (failureDetectionService != null) {
                    boolean unhealthy = failureDetectionService.isNodeUnhealthy("node-" + info.getNodeId());
                    info.setNodeStatus(unhealthy ? "FAILED" : "HEALTHY");
                } else {
                    info.setNodeStatus("HEALTHY");
                }
            }

            // Sort by nodeId for stable ordering
            skewList.sort(java.util.Comparator.comparingInt(ClockSkewInfo::getNodeId));

            int inSyncCount = getInSyncNodeCount(); // already includes self
            long threshold = timeSyncProperties.getClock_skew_threshold_ms();

            return SkewReport.builder()
                    .nodeId(nodeId)
                    .timestamp(System.currentTimeMillis())
                    .maxClockSkew(maxClockSkew)
                    .threshold(threshold)
                    .alertActive(alertActive)
                    .inSyncNodeCount(inSyncCount)
                    .totalNodes(totalNodes)
                    .totalRemoteNodes(skewMap.size())
                    .alertNodeCount((int) skewList.stream().filter(ClockSkewInfo::isAlertTriggered).count())
                    .skewDetails(skewList)
                    .build();
        } finally {
            skewLock.readLock().unlock();
        }
    }

    @lombok.Data
    @lombok.Builder
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class SkewReport {
        private int nodeId;
        private long timestamp;
        private long maxClockSkew;
        private long threshold;
        private boolean alertActive;
        private int inSyncNodeCount;
        private int totalNodes;        // full cluster size (includes self)
        private int totalRemoteNodes;  // remote nodes measured so far
        private int alertNodeCount;
        private java.util.List<ClockSkewInfo> skewDetails;
    }
}
