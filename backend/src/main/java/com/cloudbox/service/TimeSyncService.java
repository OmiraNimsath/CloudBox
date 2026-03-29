package com.cloudbox.service;

import com.cloudbox.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Unified Time Synchronization Service.
 *
 * Integrates ClockSynchronizer and SkewDetector to provide a cohesive
 * time synchronization interface for the entire CloudBox system.
 *
 * Serves as the facade for:
 * - Replication module: timestamp-based conflict resolution
 * - Consensus module: event ordering in leader election
 * - Fault tolerance module: event tracking and recovery
 */
@Slf4j
@Service
public class TimeSyncService {

    private final ClockSynchronizer clockSynchronizer;
    private final SkewDetector skewDetector;
    private final int nodeId;

    public TimeSyncService(
            ClockSynchronizer clockSynchronizer,
            SkewDetector skewDetector,
            @Value("${cloudbox.node-id:1}") int nodeId) {
        this.clockSynchronizer = clockSynchronizer;
        this.skewDetector = skewDetector;
        this.nodeId = nodeId;

        log.info("TimeSyncService initialized for node {}", nodeId);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Clock-based Operations
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Get current Hybrid Logical Clock for event timestamping.
     * Used by replication and consensus modules.
     */
    public HybridLogicalClock getCurrentHLC() {
        return clockSynchronizer.getCurrentHLC();
    }

    /**
     * Get current Lamport logical timestamp.
     * Used for pure causal ordering when physical time is not reliable.
     */
    public LogicalTimestamp getCurrentLogicalTimestamp() {
        return clockSynchronizer.getCurrentLogicalTimestamp();
    }

    /**
     * Get current monotonically increasing timestamp.
     * Safe for use as sequence numbers or event IDs.
     */
    public long getCurrentTimestamp() {
        return clockSynchronizer.getCurrentHLC().getPhysicalTime();
    }

    /**
     * Increment clocks for an outgoing event.
     * Must be called before broadcasting this node's state.
     */
    public void recordEventSend() {
        clockSynchronizer.updateOnSend();
        log.debug("Event send recorded: HLC={}, LogicalTS={}",
                clockSynchronizer.getCurrentHLC(),
                clockSynchronizer.getCurrentLogicalTimestamp());
    }

    /**
     * Merge remote clocks upon receiving an event.
     * Ensures causal relationships are preserved.
     *
     * @param remoteHLC      Remote node's HLC
     * @param remoteLogical  Remote node's logical timestamp
     */
    public void recordEventReceive(HybridLogicalClock remoteHLC, LogicalTimestamp remoteLogical) {
        clockSynchronizer.updateOnReceive(remoteHLC, remoteLogical);
        log.debug("Event received: remote HLC={}, remote LogicalTS={}",
                remoteHLC != null ? remoteHLC.getPhysicalTime() : "null",
                remoteLogical != null ? remoteLogical.getTimestamp() : "null");
    }

    /**
     * Compare two hybrid logical clocks for ordering.
     * Returns: negative if clock1 < clock2, positive if clock1 > clock2, 0 if equal.
     */
    public int compareHLC(HybridLogicalClock clock1, HybridLogicalClock clock2) {
        if (clock1 == null || clock2 == null) {
            return 0;
        }
        return clock1.compareTo(clock2);
    }

    /**
     * Check causality between two events using logical timestamps.
     */
    public boolean happensBefore(LogicalTimestamp ts1, LogicalTimestamp ts2) {
        if (ts1 == null || ts2 == null) {
            return false;
        }
        return ts1.compareTo(ts2) < 0;
    }

    /**
     * Check if two events are concurrent (neither happened before the other).
     */
    public boolean isConcurrent(VectorClock vc1, VectorClock vc2) {
        if (vc1 == null || vc2 == null) {
            return false;
        }
        return vc1.isConcurrent(vc2);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Skew Detection & Monitoring
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Get current clock skew with a specific node.
     *
     * @param remoteNodeId The remote node ID
     * @return Skew in milliseconds (positive = remote ahead, negative = remote behind)
     */
    public long getClockSkewWithNode(int remoteNodeId) {
        return skewDetector.getSkewInfo(remoteNodeId).getSkewMillis();
    }

    /**
     * Get maximum clock skew observed in the cluster.
     */
    public long getMaxClockSkew() {
        return skewDetector.getMaxClockSkew();
    }

    /**
     * Check if any node has clock skew exceeding threshold.
     */
    public boolean isClockSkewAlertActive() {
        return skewDetector.isAlertActive();
    }

    /**
     * Get count of nodes with excessive clock skew.
     */
    public int getSkewAlertNodeCount() {
        return skewDetector.getAlertNodeCount();
    }

    /**
     * Get count of nodes in acceptable sync range.
     */
    public int getInSyncNodeCount() {
        return skewDetector.getInSyncNodeCount();
    }

    /**
     * Get detailed skew information for all nodes.
     */
    public Map<Integer, ClockSkewInfo> getAllClockSkewInfo() {
        return skewDetector.getAllSkewInfo();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Status Reporting
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Get comprehensive time synchronization status.
     * Used by /api/timesync/status endpoint and monitoring systems.
     */
    public TimeSyncStatus getStatus() {
        HybridLogicalClock hlc = clockSynchronizer.getCurrentHLC();
        LogicalTimestamp logTS = clockSynchronizer.getCurrentLogicalTimestamp();
        SkewDetector.SkewReport skewReport = skewDetector.generateReport();

        Map<Integer, ClockSkewInfo> skewMap = new HashMap<>(skewDetector.getAllSkewInfo());

        return TimeSyncStatus.builder()
                .nodeId(nodeId)
                .localTime(System.currentTimeMillis())
                .hlcPhysicalTime(hlc.getPhysicalTime())
                .hlcLogicalCounter(hlc.getLogicalCounter())
                .logicalTimestamp(logTS.getTimestamp())
                .synced(!skewDetector.isAlertActive())
                .maxClockSkew(skewReport.getMaxClockSkew())
                .syncedNodeCount(skewReport.getInSyncNodeCount()) // already includes self
                .totalNodes(skewReport.getTotalNodes())
                .lastSyncAt(skewReport.getTimestamp())
                .ntpOffsetMs(clockSynchronizer.getSystemTimeOffset())
                .lastNtpSyncAt(clockSynchronizer.getLastNtpSyncTime())
                .nodeSkewMap(skewMap)
                .build();
    }

    /**
     * Get skew report for detailed monitoring.
     */
    public SkewDetector.SkewReport getSkewReport() {
        return skewDetector.generateReport();
    }

    /**
     * Get clock synchronizer metrics.
     */
    public ClockSynchronizer.SyncMetrics getSyncMetrics() {
        return clockSynchronizer.getMetrics();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Timestamp Generation & Comparison (High-level API)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Create a new timestamp for an event.
     * Automatically records the event send.
     *
     * @return Timestamp with node ID
     */
    public EventTimestamp createEventTimestamp() {
        recordEventSend();
        HybridLogicalClock hlc = clockSynchronizer.getCurrentHLC();
        LogicalTimestamp logTS = clockSynchronizer.getCurrentLogicalTimestamp();

        return EventTimestamp.builder()
                .nodeId(nodeId)
                .hlc(hlc.copy())
                .logicalTimestamp(logTS.getTimestamp())
                .physicalTime(System.currentTimeMillis())
                .build();
    }

    /**
     * Process received timestamps from remote event.
     * Merges the remote clocks for causality.
     */
    public void mergeRemoteTimestamp(EventTimestamp remoteTimestamp) {
        if (remoteTimestamp == null) {
            recordEventSend();
            return;
        }

        HybridLogicalClock remoteHLC = remoteTimestamp.getHlc();
        LogicalTimestamp remoteLogTS = LogicalTimestamp.builder()
                .timestamp(remoteTimestamp.getLogicalTimestamp())
                .nodeId(remoteTimestamp.getNodeId())
                .build();

        recordEventReceive(remoteHLC, remoteLogTS);
    }

    /**
     * Compare two event timestamps for ordering.
     * Returns:
     *   - negative if event1 happened before event2
     *   - positive if event1 happened after event2
     *   - 0 if concurrent or equal
     */
    public int compareEventTimestamps(EventTimestamp ts1, EventTimestamp ts2) {
        if (ts1 == null || ts2 == null) {
            return 0;
        }

        // Compare by HLC first
        int hlcCmp = ts1.getHlc().compareTo(ts2.getHlc());
        if (hlcCmp != 0) {
            return hlcCmp;
        }

        // If HLC equal, compare by logical timestamp
        return Long.compare(ts1.getLogicalTimestamp(), ts2.getLogicalTimestamp());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Maintenance & Diagnostics
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Health check for time synchronization module.
     */
    public TimeSyncHealth healthCheck() {
        boolean clockSkewHealthy = !skewDetector.isAlertActive();
        int inSyncNodes = skewDetector.getInSyncNodeCount();
        int totalRemoteNodes = skewDetector.getAllSkewInfo().size();
        boolean allNodesSync = (inSyncNodes == totalRemoteNodes && inSyncNodes > 0) || totalRemoteNodes == 0;

        String status = (clockSkewHealthy && allNodesSync) ? "HEALTHY" : "DEGRADED";

        return TimeSyncHealth.builder()
                .nodeId(nodeId)
                .status(status)
                .clockSkewHealthy(clockSkewHealthy)
                .allNodesInSync(allNodesSync)
                .inSyncNodeCount(inSyncNodes)
                .maxClockSkew(skewDetector.getMaxClockSkew())
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * Clear skew detector data for testing/reset.
     */
    public void clearSkewData() {
        skewDetector.clearSkewData();
        log.info("Skew data cleared on node {}", nodeId);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Data Models
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Complete event timestamp for inter-node communication.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class EventTimestamp {
        private int nodeId;
        private HybridLogicalClock hlc;
        private long logicalTimestamp;
        private long physicalTime;
    }

    /**
     * Health status of time synchronization module.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class TimeSyncHealth {
        private int nodeId;
        private String status;           // HEALTHY, DEGRADED
        private boolean clockSkewHealthy;
        private boolean allNodesInSync;
        private int inSyncNodeCount;
        private long maxClockSkew;
        private long timestamp;
    }
}
