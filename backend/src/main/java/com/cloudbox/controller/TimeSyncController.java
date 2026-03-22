package com.cloudbox.controller;

import com.cloudbox.model.*;
import com.cloudbox.service.ClockSynchronizer;
import com.cloudbox.service.SkewDetector;
import com.cloudbox.service.TimeSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST Controller for Time Synchronization endpoints.
 *
 * Exposes time sync status, metrics, and diagnostics.
 * Used by:
 * - Monitoring systems: track clock skew and sync status
 * - Other nodes: retrieve current time for skew detection
 * - Clients: visibility into cluster time coordination
 */
@Slf4j
@RestController
@RequestMapping("/api/timesync")
public class TimeSyncController {

    private final TimeSyncService timeSyncService;

    public TimeSyncController(TimeSyncService timeSyncService) {
        this.timeSyncService = timeSyncService;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Status & Health Endpoints
    // ──────────────────────────────────────────────────────────────────────

    /**
     * GET /api/timesync/status
     * Get comprehensive time synchronization status for this node.
     *
     * @return TimeSyncStatus with HLC, logical timestamp, skew info
     */
    @GetMapping("/status")
    public ApiResponse<TimeSyncStatus> getTimeSyncStatus() {
        try {
            TimeSyncStatus status = timeSyncService.getStatus();
            log.debug("Time sync status requested: node={}, synced={}, maxSkew={}ms",
                    status.getNodeId(), status.isSynced(), status.getMaxClockSkew());
            return ApiResponse.ok(status);
        } catch (Exception e) {
            log.error("Failed to get time sync status", e);
            return ApiResponse.error("Failed to retrieve time sync status");
        }
    }

    /**
     * GET /api/timesync/health
     * Health check for time synchronization module.
     *
     * @return Health status (HEALTHY or DEGRADED)
     */
    @GetMapping("/health")
    public ApiResponse<TimeSyncService.TimeSyncHealth> getHealth() {
        try {
            TimeSyncService.TimeSyncHealth health = timeSyncService.healthCheck();
            log.debug("Time sync health checked: status={}", health.getStatus());
            return ApiResponse.ok(health);
        } catch (Exception e) {
            log.error("Failed to check time sync health", e);
            return ApiResponse.error("Failed to check time sync health");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Time Retrieval Endpoints (for inter-node synchronization)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * GET /api/timesync/time
     * Get current system time (milliseconds since epoch).
     * Used by SkewDetector to measure clock skew with this node.
     *
     * @return Current time in milliseconds
     */
    @GetMapping("/time")
    public Long getCurrentTime() {
        try {
            long currentTime = System.currentTimeMillis();
            log.trace("Current time requested: {}ms", currentTime);
            return currentTime;
        } catch (Exception e) {
            log.error("Failed to get current time", e);
            return null;
        }
    }

    /**
     * GET /api/timesync/hlc
     * Get current Hybrid Logical Clock.
     * Used by other nodes for causality preservation in distributed events.
     *
     * @return Current HLC
     */
    @GetMapping("/hlc")
    public ApiResponse<HybridLogicalClock> getCurrentHLC() {
        try {
            HybridLogicalClock hlc = timeSyncService.getCurrentHLC();
            return ApiResponse.ok(hlc);
        } catch (Exception e) {
            log.error("Failed to get current HLC", e);
            return ApiResponse.error("Failed to retrieve current HLC");
        }
    }

    /**
     * GET /api/timesync/logical-timestamp
     * Get current Lamport logical timestamp.
     * Used for event ordering in consensus and replication.
     *
     * @return LogicalTimestamp with node context
     */
    @GetMapping("/logical-timestamp")
    public ApiResponse<LogicalTimestamp> getLogicalTimestamp() {
        try {
            LogicalTimestamp ts = timeSyncService.getCurrentLogicalTimestamp();
            return ApiResponse.ok(ts);
        } catch (Exception e) {
            log.error("Failed to get logical timestamp", e);
            return ApiResponse.error("Failed to retrieve logical timestamp");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Clock Skew Monitoring
    // ──────────────────────────────────────────────────────────────────────

    /**
     * GET /api/timesync/skew
     * Get clock skew information across the cluster.
     *
     * @return Map of nodeId -> ClockSkewInfo
     */
    @GetMapping("/skew")
    public ApiResponse<Map<Integer, ClockSkewInfo>> getClockSkewInfo() {
        try {
            Map<Integer, ClockSkewInfo> skewMap = timeSyncService.getAllClockSkewInfo();
            log.debug("Clock skew info requested: nodes={}", skewMap.size());
            return ApiResponse.ok(skewMap);
        } catch (Exception e) {
            log.error("Failed to get clock skew info", e);
            return ApiResponse.error("Failed to retrieve clock skew info");
        }
    }

    /**
     * GET /api/timesync/skew/max
     * Get maximum observed clock skew in the cluster.
     *
     * @return Maximum skew in milliseconds
     */
    @GetMapping("/skew/max")
    public ApiResponse<Long> getMaxClockSkew() {
        try {
            long maxSkew = timeSyncService.getMaxClockSkew();
            return ApiResponse.ok(maxSkew);
        } catch (Exception e) {
            log.error("Failed to get max clock skew", e);
            return ApiResponse.error("Failed to retrieve max clock skew");
        }
    }

    /**
     * GET /api/timesync/skew/alert-active
     * Check if any clock skew exceeds the alert threshold.
     *
     * @return true if alert is active, false otherwise
     */
    @GetMapping("/skew/alert-active")
    public ApiResponse<Boolean> isClockSkewAlertActive() {
        try {
            boolean alertActive = timeSyncService.isClockSkewAlertActive();
            if (alertActive) {
                log.warn("Clock skew alert is ACTIVE. Alert count: {}",
                        timeSyncService.getSkewAlertNodeCount());
            }
            return ApiResponse.ok(alertActive);
        } catch (Exception e) {
            log.error("Failed to check clock skew alert status", e);
            return ApiResponse.error("Failed to check clock skew alert status");
        }
    }

    /**
     * GET /api/timesync/skew/in-sync-count
     * Get count of nodes in acceptable sync range.
     *
     * @return Number of synchronized nodes
     */
    @GetMapping("/skew/in-sync-count")
    public ApiResponse<Integer> getInSyncNodeCount() {
        try {
            int inSyncCount = timeSyncService.getInSyncNodeCount();
            return ApiResponse.ok(inSyncCount);
        } catch (Exception e) {
            log.error("Failed to get in-sync node count", e);
            return ApiResponse.error("Failed to retrieve in-sync node count");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Detailed Reports
    // ──────────────────────────────────────────────────────────────────────

    /**
     * GET /api/timesync/skew-report
     * Get detailed clock skew report for all nodes.
     * Useful for debugging and operations monitoring.
     *
     * @return Comprehensive SkewReport
     */
    @GetMapping("/skew-report")
    public ApiResponse<SkewDetector.SkewReport> getSkewReport() {
        try {
            SkewDetector.SkewReport report = timeSyncService.getSkewReport();
            log.debug("Skew report requested: maxSkew={}ms, inSync={}/{}",
                    report.getMaxClockSkew(), report.getInSyncNodeCount(),
                    report.getTotalRemoteNodes());
            return ApiResponse.ok(report);
        } catch (Exception e) {
            log.error("Failed to generate skew report", e);
            return ApiResponse.error("Failed to generate skew report");
        }
    }

    /**
     * GET /api/timesync/metrics
     * Get raw synchronization metrics from ClockSynchronizer.
     *
     * @return SyncMetrics with HLC and timestamp information
     */
    @GetMapping("/metrics")
    public ApiResponse<ClockSynchronizer.SyncMetrics> getSyncMetrics() {
        try {
            ClockSynchronizer.SyncMetrics metrics = timeSyncService.getSyncMetrics();
            return ApiResponse.ok(metrics);
        } catch (Exception e) {
            log.error("Failed to get sync metrics", e);
            return ApiResponse.error("Failed to retrieve sync metrics");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Diagnostic & Testing Endpoints
    // ──────────────────────────────────────────────────────────────────────

    /**
     * POST /api/timesync/clear-skew-data
     * Clear all recorded skew measurements (for testing/reset).
     * Should only be used in development/testing environments.
     */
    @PostMapping("/clear-skew-data")
    public ApiResponse<String> clearSkewData() {
        try {
            timeSyncService.clearSkewData();
            log.info("Skew data cleared via API request");
            return ApiResponse.ok("Skew data cleared successfully");
        } catch (Exception e) {
            log.error("Failed to clear skew data", e);
            return ApiResponse.error("Failed to clear skew data");
        }
    }

    /**
     * GET /api/timesync/info
     * Get general time sync module information and configuration.
     *
     * @return Info map with module details
     */
    @GetMapping("/info")
    public ApiResponse<Map<String, Object>> getModuleInfo() {
        try {
            Map<String, Object> info = Map.of(
                    "module", "TimeSynchronization",
                    "status", timeSyncService.healthCheck().getStatus(),
                    "synced", !timeSyncService.isClockSkewAlertActive(),
                    "maxClockSkew", timeSyncService.getMaxClockSkew(),
                    "inSyncNodes", timeSyncService.getInSyncNodeCount(),
                    "timestamp", System.currentTimeMillis()
            );
            return ApiResponse.ok(info);
        } catch (Exception e) {
            log.error("Failed to get module info", e);
            return ApiResponse.error("Failed to retrieve module info");
        }
    }
}
