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

            // Update global max skew
            maxClockSkew = skewMap.values().stream()
                    .mapToLong(info -> Math.abs(info.getSkewMillis()))
                    .max()
                    .orElse(0);

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
     * Measure skew with a specific remote node.
     * Fetches remote node's current time and compares with local time.
     */
    private void measureSkewWithNode(int remoteNodeId) {
        try {
            String remoteUrl = String.format("http://localhost:%d/api/timesync/time",
                    8080 + remoteNodeId - 1);

            // Try to fetch remote time
            Long remoteTime = restTemplate.getForObject(remoteUrl, Long.class);
            if (remoteTime != null) {
                long localTime = System.currentTimeMillis();
                long skew = remoteTime - localTime; // Positive = remote ahead

                recordSkew(remoteNodeId, skew);

                log.debug("Skew measurement node {} -> node {}: {}ms",
                        nodeId, remoteNodeId, skew);
            }
        } catch (Exception e) {
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
     */
    public int getInSyncNodeCount() {
        skewLock.readLock().lock();
        try {
            long threshold = timeSyncProperties.getClock_skew_threshold_ms();
            return (int) skewMap.values().stream()
                    .filter(info -> Math.abs(info.getSkewMillis()) <= threshold)
                    .count();
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
     */
    public SkewReport generateReport() {
        skewLock.readLock().lock();
        try {
            List<ClockSkewInfo> skewList = new ArrayList<>(skewMap.values());
            int inSyncCount = getInSyncNodeCount();
            long threshold = timeSyncProperties.getClock_skew_threshold_ms();

            return SkewReport.builder()
                    .nodeId(nodeId)
                    .timestamp(System.currentTimeMillis())
                    .maxClockSkew(maxClockSkew)
                    .threshold(threshold)
                    .alertActive(alertActive)
                    .inSyncNodeCount(inSyncCount)
                    .totalRemoteNodes(skewMap.size())
                    .skewDetails(skewList)
                    .build();
        } finally {
            skewLock.readLock().unlock();
        }
    }

    /**
     * Report structure for detailed skew analytics.
     */
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
        private int totalRemoteNodes;
        private List<ClockSkewInfo> skewDetails;
    }
}
