package com.cloudbox.service;

import com.cloudbox.config.ClusterConfig;
import com.cloudbox.config.TimeSyncProperties;
import com.cloudbox.model.HybridLogicalClock;
import com.cloudbox.model.LogicalTimestamp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Service responsible for synchronizing clocks across the cluster.
 *
 * Implements NTP-like protocol with fallback to system time.
 * Maintains both Lamport logical clocks and Hybrid Logical Clocks (HLC)
 * for event ordering and causality preservation.
 */
@Slf4j
@Service
public class ClockSynchronizer {

    private final TimeSyncProperties timeSyncProperties;
    private final RestTemplate restTemplate;
    private final int nodeId;
    private final int totalNodes;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.cloudbox.controller.HealthController healthController;

    // Thread-safe clock state
    private final ReentrantReadWriteLock clockLock = new ReentrantReadWriteLock();

    private HybridLogicalClock hybridLogicalClock;
    private LogicalTimestamp logicalTimestamp;
    private long systemTimeOffset = 0; // Offset between system time and NTP time
    private volatile long lastNtpSyncTime = 0; // Timestamp of last successful Cristian sync

    public ClockSynchronizer(
            TimeSyncProperties timeSyncProperties,
            RestTemplate restTemplate,
            @Value("${cloudbox.node-id:1}") int nodeId,
            @Value("${cloudbox.cluster-size:5}") int totalNodes) {
        this.timeSyncProperties = timeSyncProperties;
        this.restTemplate = restTemplate;
        this.nodeId = nodeId;
        this.totalNodes = totalNodes;

        // Initialize clocks
        this.hybridLogicalClock = HybridLogicalClock.now(nodeId);
        this.logicalTimestamp = LogicalTimestamp.builder()
                .timestamp(0)
                .nodeId(nodeId)
                .build();

        log.info("ClockSynchronizer initialized for node {}", nodeId);
    }

    /**
     * Get current Hybrid Logical Clock.
     * This is thread-safe and suitable for timestamping events.
     */
    public HybridLogicalClock getCurrentHLC() {
        clockLock.writeLock().lock();
        try {
            // Advance physical time on every read so UI always shows current wall-clock time
            hybridLogicalClock.updateSend(nodeId);
            return hybridLogicalClock.copy();
        } finally {
            clockLock.writeLock().unlock();
        }
    }

    /**
     * Get current Lamport logical timestamp.
     */
    public LogicalTimestamp getCurrentLogicalTimestamp() {
        clockLock.readLock().lock();
        try {
            LogicalTimestamp copy = LogicalTimestamp.builder()
                    .timestamp(logicalTimestamp.getTimestamp())
                    .nodeId(logicalTimestamp.getNodeId())
                    .build();
            return copy;
        } finally {
            clockLock.readLock().unlock();
        }
    }

    /**
     * Update local clocks upon sending an event.
     * Both HLC and logical timestamp are incremented.
     */
    public void updateOnSend() {
        clockLock.writeLock().lock();
        try {
            hybridLogicalClock.updateSend(nodeId);
            logicalTimestamp.increment();
        } finally {
            clockLock.writeLock().unlock();
        }
    }

    /**
     * Update local clocks upon receiving a remote event with remote timestamps.
     */
    public void updateOnReceive(HybridLogicalClock remoteHLC, LogicalTimestamp remoteLogical) {
        clockLock.writeLock().lock();
        try {
            hybridLogicalClock.updateReceive(remoteHLC, nodeId);
            if (remoteLogical != null) {
                logicalTimestamp.update(remoteLogical);
            } else {
                logicalTimestamp.increment();
            }
        } finally {
            clockLock.writeLock().unlock();
        }
    }

    /**
     * Adjust system time offset based on NTP measurement.
     * Implements gradual or instant adjustment based on configuration.
     *
     * @param ntpOffset Offset in milliseconds (positive = system ahead, negative = system behind)
     */
    public void adjustTimeOffset(long ntpOffset) {
        clockLock.writeLock().lock();
        try {
            String strategy = timeSyncProperties.getClock_adjustment_strategy();

            if ("instant".equalsIgnoreCase(strategy)) {
                // Instant adjustment
                this.systemTimeOffset = ntpOffset;
                log.info("Instant clock adjustment: offset = {} ms, total offset = {} ms",
                        ntpOffset, this.systemTimeOffset);
            } else {
                // Gradual adjustment: move halfway toward the measured offset each cycle (EWMA)
                this.systemTimeOffset = this.systemTimeOffset + (ntpOffset - this.systemTimeOffset) / 2;
                log.info("Gradual clock adjustment: measured offset = {} ms, total offset = {} ms",
                        ntpOffset, this.systemTimeOffset);
            }
        } finally {
            clockLock.writeLock().unlock();
        }
    }

    /**
     * Get the current system time offset (ms).
     * Positive = system ahead, negative = system behind.
     */
    public long getSystemTimeOffset() {
        clockLock.readLock().lock();
        try {
            return systemTimeOffset;
        } finally {
            clockLock.readLock().unlock();
        }
    }

    /**
     * Get the adjusted physical time based on system offset.
     */
    public long getAdjustedPhysicalTime() {
        return System.currentTimeMillis() - systemTimeOffset;
    }

    /**
     * Get the timestamp of the last successful Cristian/NTP synchronization.
     */
    public long getLastNtpSyncTime() {
        return lastNtpSyncTime;
    }

    /**
     * Synchronize clocks with NTP server or system time (scheduled task).
     * Runs at configured interval.
     */
    @Scheduled(
            initialDelay = 5000, // Wait 5s after startup
            fixedDelayString = "${cloudbox.timesync.ntp-sync-interval}",
            timeUnit = java.util.concurrent.TimeUnit.MILLISECONDS
    )
    public void synchronizeClocks() {
        if (healthController != null && healthController.isSimulatingFailure()) {
            return;
        }
        try {
            if (timeSyncProperties.isEnable_ntp()) {
                synchronizeWithNTP();
            } else {
                synchronizeWithSystemTime();
            }
        } catch (RuntimeException e) {
            log.error("Clock synchronization failed", e);
            // Fallback to system time
            synchronizeWithSystemTime();
        }
    }

    /**
     * Synchronize with a reference node using Cristian's Algorithm.
     *
     * Cristian's Algorithm:
     *   1. Record T0 (local time before request)
     *   2. Request current time from reference node
     *   3. Record T1 (local time after response)
     *   4. RTT = T1 - T0
     *   5. Estimated server time at receipt = serverTime + RTT / 2
     *   6. Offset = estimatedServerTime - T1
     */
    private void synchronizeWithNTP() {
        for (int remoteId = 1; remoteId <= totalNodes; remoteId++) {
            if (remoteId == nodeId) {
                continue;
            }
            try {
                String url = ClusterConfig.getNodeUrl(remoteId) + "/api/timesync/time";

                long t0 = System.currentTimeMillis();
                Long serverTime = restTemplate.getForObject(url, Long.class);
                long t1 = System.currentTimeMillis();

                if (serverTime == null) {
                    continue;
                }

                long rtt = t1 - t0;
                long estimatedServerNow = serverTime + rtt / 2;
                long offset = t1 - estimatedServerNow;

                adjustTimeOffset(offset);
                lastNtpSyncTime = System.currentTimeMillis();
                // Tick the Lamport clock — synchronization is an event
                clockLock.writeLock().lock();
                try { logicalTimestamp.increment(); } finally { clockLock.writeLock().unlock(); }

                log.debug("Cristian sync with node {}: T0={}, serverTime={}, T1={}, RTT={}ms, offset={}ms",
                        remoteId, t0, serverTime, t1, rtt, offset);
                return; // Success — one reference node is sufficient
            } catch (RuntimeException e) {
                log.debug("Cristian sync failed for node {}: {}", remoteId, e.getMessage());
            }
        }
        log.warn("All reference nodes unreachable, falling back to system time");
        synchronizeWithSystemTime();
    }

    /**
     * Synchronize with system time (fallback when NTP unavailable).
     */
    private void synchronizeWithSystemTime() {
        clockLock.writeLock().lock();
        try {
            // Reset offset to 0, trust system time
            systemTimeOffset = 0;
            log.debug("Using system time for synchronization (offset reset)");
        } finally {
            clockLock.writeLock().unlock();
        }
    }

    /**
     * Get synchronization status metrics.
     */
    public SyncMetrics getMetrics() {
        clockLock.readLock().lock();
        try {
            return SyncMetrics.builder()
                    .nodeId(nodeId)
                    .currentHLC(hybridLogicalClock.copy())
                    .currentLogicalTimestamp(logicalTimestamp.getTimestamp())
                    .systemTimeOffset(systemTimeOffset)
                    .adjustedPhysicalTime(getAdjustedPhysicalTime())
                    .lastSyncTime(lastNtpSyncTime)
                    .build();
        } finally {
            clockLock.readLock().unlock();
        }
    }

    /**
     * Metrics structure for synchronization status.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class SyncMetrics {
        private int nodeId;
        private HybridLogicalClock currentHLC;
        private long currentLogicalTimestamp;
        private long systemTimeOffset;
        private long adjustedPhysicalTime;
        private long lastSyncTime;
    }
}
