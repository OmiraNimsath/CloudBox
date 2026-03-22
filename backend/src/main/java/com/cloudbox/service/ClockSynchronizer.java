package com.cloudbox.service;

import com.cloudbox.config.TimeSyncProperties;
import com.cloudbox.model.HybridLogicalClock;
import com.cloudbox.model.LogicalTimestamp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
    private final int nodeId;

    // Thread-safe clock state
    private final ReentrantReadWriteLock clockLock = new ReentrantReadWriteLock();

    private HybridLogicalClock hybridLogicalClock;
    private LogicalTimestamp logicalTimestamp;
    private long systemTimeOffset = 0; // Offset between system time and NTP time

    public ClockSynchronizer(
            TimeSyncProperties timeSyncProperties,
            @Value("${cloudbox.node-id:1}") int nodeId) {
        this.timeSyncProperties = timeSyncProperties;
        this.nodeId = nodeId;

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
        clockLock.readLock().lock();
        try {
            return hybridLogicalClock.copy();
        } finally {
            clockLock.readLock().unlock();
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
                // Gradual adjustment (damping)
                long adjustment = ntpOffset / 2; // Dampen by 50%
                this.systemTimeOffset = (this.systemTimeOffset + adjustment) / 2;
                log.info("Gradual clock adjustment: offset delta = {} ms, total offset = {} ms",
                        adjustment, this.systemTimeOffset);
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
     * Synchronize clocks with NTP server or system time (scheduled task).
     * Runs at configured interval.
     */
    @Scheduled(
            initialDelay = 5000, // Wait 5s after startup
            fixedDelayString = "${cloudbox.timesync.ntp-sync-interval}",
            timeUnit = java.util.concurrent.TimeUnit.MILLISECONDS
    )
    public void synchronizeClocks() {
        try {
            if (timeSyncProperties.isEnable_ntp()) {
                synchronizeWithNTP();
            } else {
                synchronizeWithSystemTime();
            }
        } catch (Exception e) {
            log.error("Clock synchronization failed", e);
            // Fallback to system time
            synchronizeWithSystemTime();
        }
    }

    /**
     * Synchronize with NTP server (simulated).
     * In production, this would use Apache Commons Net or similar.
     */
    private void synchronizeWithNTP() {
        try {
            // Simulate NTP synchronization
            // In production: use NTPUDPClient from commons-net
            long localTime = System.currentTimeMillis();

            // For now, simulate small random NTP offset (in real implementation,
            // this would query actual NTP server)
            long ntpTime = System.currentTimeMillis();
            long offset = (long) (Math.random() * 20 - 10); // Random ±10ms

            adjustTimeOffset(offset);

            log.debug("NTP sync: localTime={}, ntpTime={}, offset={}ms",
                    localTime, ntpTime, offset);
        } catch (Exception e) {
            log.warn("NTP synchronization failed, falling back to system time", e);
            synchronizeWithSystemTime();
        }
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
                    .lastSyncTime(System.currentTimeMillis())
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
