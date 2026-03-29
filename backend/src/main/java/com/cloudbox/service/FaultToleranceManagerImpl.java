package com.cloudbox.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.cloudbox.model.FaultStatus;
import com.cloudbox.model.NodeHealth;
import com.cloudbox.model.RecoveryTask;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of FaultToleranceManager.
 * Orchestrates heartbeat monitoring, failure detection, and recovery.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaultToleranceManagerImpl implements FaultToleranceManager {
    
    private final HeartbeatMonitor heartbeatMonitor;
    private final FailureDetectionService failureDetectionService;
    private final RecoveryManager recoveryManager;
    
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    /** Wall-clock millis when the fault-tolerance system was initialized. */
    private volatile long systemStartMs = 0;
    /** Time (ms) when quorum was first lost in the current outage episode; -1 when quorum is held. */
    private volatile long quorumLossStartMs = -1;
    /** Cumulative count of quorum-loss outage events. */
    private final AtomicLong quorumLossCount = new AtomicLong(0);
    /** Time (ms) when each node was first detected as failed this episode. */
    private final Map<String, Long> failureStartMs = new ConcurrentHashMap<>();
    /** Collected real MTTR samples (seconds) from completed quorum-loss outages. */
    private final java.util.List<Double> mttrSamples = new java.util.concurrent.CopyOnWriteArrayList<>();

    // ── Time-weighted average replication ──────────────────────────────
    private volatile long   replicationLastUpdateMs  = 0;
    private volatile double replicationWeightedSum   = 0.0;
    private volatile double replicationTotalTimeMs   = 0.0;

    @Override
    public void initialize() {
        if (initialized.compareAndSet(false, true)) {
            systemStartMs = System.currentTimeMillis();
            failureDetectionService.initialize();
            recoveryManager.initialize();
            log.info("Fault tolerance system initialized");
        }
    }
    
    @Override
    public void shutdown() {
        if (initialized.compareAndSet(true, false)) {
            recoveryManager.shutdown();
            failureDetectionService.shutdown();
            log.info("Fault tolerance system shutdown");
        }
    }
    
    @Override
    public FaultStatus getFaultStatus() {
        Map<String, NodeHealth> nodeHealthMap = failureDetectionService.getAllNodeHealth();
        List<String> healthyNodes = failureDetectionService.getHealthyNodes();
        List<String> failedNodes = failureDetectionService.getFailedNodes();
        
        int healthyCount = healthyNodes.size();
        int failedCount = failedNodes.size();
        int totalNodes = nodeHealthMap.size();
        
        // Determine cluster state
        String clusterState = determineClusterState(healthyCount, failedCount, totalNodes);
        
        // Track failure start times for MTTR calculation
        trackFailureTimes(nodeHealthMap);
        // Track quorum-loss transitions for MTTF
        trackQuorumLoss(failureDetectionService.hasQuorum());

        // Update time-weighted replication average before building the snapshot
        updateReplicationAverage(nodeHealthMap);

        // Compute reliability metrics once (avoids repeated side-effectful calls)
        List<RecoveryTask> completedTasks = recoveryManager.getCompletedRecoveryTasks();
        List<RecoveryTask> activeTasks    = recoveryManager.getActiveRecoveryTasks();
        double mttf  = calculateMttf();
        double mttr  = calculateMttr();
        double avail = quorumLossCount.get() == 0 ? 100.0 : calculateAvailability(mttf, mttr);

        // Aggregate data
        FaultStatus status = FaultStatus.builder()
            .timestamp(LocalDateTime.now())
            .clusterState(clusterState)
            .totalNodes(totalNodes)
            .healthyNodes(healthyCount)
            .failedNodes(failedCount)
            .recoveringNodes(getRecoveringNodeCount(nodeHealthMap))
            .nodeHealthMap(nodeHealthMap)
            .activeRecoveryTasks(activeTasks)
            .completedRecoveryTasks(completedTasks)
            .underReplicatedFileCount(recoveryManager.getUnderReplicatedFileCount())
            .averageReplicationFactor(calculateAverageReplicationFactor(nodeHealthMap))
            .hasQuorum(failureDetectionService.hasQuorum())
            .lastHeartbeatTime(getLastHeartbeatTime())
            .failureDetectionStatus(getFailureDetectionStatus())
            .recentFailures(extractRecentFailures(nodeHealthMap))
            .mttfSeconds(mttf)
            .mttrSeconds(mttr)
            .availabilityPercentage(avail)
            .availabilityLabel(availabilityLabel(avail))
            .build();

        return status;
    }
    
    @Override
    public boolean isInitialized() {
        return initialized.get();
    }
    
    @Override
    public void enable() {
        if (enabled.compareAndSet(false, true)) {
            log.info("Fault tolerance system enabled");
        }
    }
    
    @Override
    public void disable() {
        if (enabled.compareAndSet(true, false)) {
            log.warn("Fault tolerance system disabled (maintenance mode)");
        }
    }
    
    @Override
    public boolean isEnabled() {
        return enabled.get();
    }
    
    /**
     * Determine overall cluster state based on healthy/failed node counts.
     */
    private String determineClusterState(int healthyCount, int failedCount, int totalNodes) {
        if (failedCount == 0) {
            return "HEALTHY";
        } else if (healthyCount >= Math.ceil(totalNodes / 2.0)) {
            return "DEGRADED";
        } else if (healthyCount >= 1) {
            return "CRITICAL";
        } else {
            return "PARTITIONED";
        }
    }
    
    /**
     * Count nodes currently in recovery process.
     */
    private int getRecoveringNodeCount(Map<String, NodeHealth> nodeHealthMap) {
        return (int) nodeHealthMap.values().stream()
            .filter(health -> "RECOVERING".equals(health.getStatus()))
            .count();
    }
    
    /**
     * Record failure start times for newly-failed nodes; clear them when nodes recover.
     * Called every time getFaultStatus() is polled.
     */
    private void trackFailureTimes(Map<String, NodeHealth> nodeHealthMap) {
        long now = System.currentTimeMillis();
        for (NodeHealth nh : nodeHealthMap.values()) {
            if (!nh.isAlive()) {
                failureStartMs.putIfAbsent(nh.getNodeId(), now);
            } else {
                failureStartMs.remove(nh.getNodeId());
            }
        }
    }

    /**
     * Track quorum-loss events for MTTF.
     * A new outage event is recorded each time quorum transitions from held → lost.
     */
    private void trackQuorumLoss(boolean hasQuorum) {
        if (!hasQuorum && quorumLossStartMs < 0) {
            // Quorum just lost — start a new outage episode
            quorumLossStartMs = System.currentTimeMillis();
            quorumLossCount.incrementAndGet();
            log.warn("Quorum lost — outage episode #{} started", quorumLossCount.get());
        } else if (hasQuorum && quorumLossStartMs >= 0) {
            // Quorum restored — finalise the outage duration as a real MTTR sample
            double durationSec = (System.currentTimeMillis() - quorumLossStartMs) / 1000.0;
            mttrSamples.add(durationSec);
            log.info("Quorum restored — MTTR sample finalised: {}s (total samples: {})",
                String.format("%.1f", durationSec), mttrSamples.size());
            quorumLossStartMs = -1;
        }
    }

    @Override
    public void recordRecovery(String nodeId) {
        // Clean up per-node failure tracking; MTTR sampling is handled quorum-wide
        // in trackQuorumLoss() so we don't double-count here.
        failureStartMs.remove(nodeId);
        log.info("Node {} marked recovered (MTTR tracked at quorum level)", nodeId);
    }

    /**
     * Update the time-weighted replication sum.
     * Called once per getFaultStatus() poll before the snapshot is built.
     */
    private void updateReplicationAverage(Map<String, NodeHealth> nodeHealthMap) {
        long now = System.currentTimeMillis();
        if (replicationLastUpdateMs > 0) {
            long delta = now - replicationLastUpdateMs;
            if (delta > 0) {
                double aliveCount = nodeHealthMap.values().stream()
                    .filter(NodeHealth::isAlive).count();
                replicationWeightedSum  += aliveCount * delta;
                replicationTotalTimeMs  += delta;
            }
        }
        replicationLastUpdateMs = now;
    }

    /**
     * Avg replication factor — time-weighted over the application's lifetime.
     * Falls back to the current snapshot on the very first poll.
     */
    private double calculateAverageReplicationFactor(Map<String, NodeHealth> nodeHealthMap) {
        if (replicationTotalTimeMs <= 0) {
            return nodeHealthMap.values().stream().filter(NodeHealth::isAlive).count();
        }
        return replicationWeightedSum / replicationTotalTimeMs;
    }
    
    /**
     * Get the last heartbeat time as a formatted string.
     */
    private String getLastHeartbeatTime() {
        return heartbeatMonitor.getAllHeartbeats().values().stream()
            .map(hb -> hb.getTimestamp())
            .filter(Objects::nonNull)
            .max(LocalDateTime::compareTo)
            .map(LocalDateTime::toString)
            .orElse("NEVER");
    }
    
    /**
     * Get failure detection status as a string.
     */
    private String getFailureDetectionStatus() {
        if (!initialized.get()) {
            return "DISABLED";
        } else if (!heartbeatMonitor.isMonitoring()) {
            return "ERROR";
        } else {
            return "ENABLED";
        }
    }
    
    /**
     * MTTF = total uptime / number of quorum-loss events (outages).
     * Losing 1-2 nodes keeps quorum and is NOT counted as an outage.
     * Only when 3+ nodes fail (quorum lost) does MTTF tick down.
     * Returns current uptime when no outage has occurred yet.
     */
    private double calculateMttf() {
        if (systemStartMs <= 0) return -1.0;
        // During an active outage, freeze MTTF at the moment quorum was lost.
        // This prevents MTTF from climbing while the system is down.
        long endMs = quorumLossStartMs >= 0 ? quorumLossStartMs : System.currentTimeMillis();
        long uptimeSeconds = (endMs - systemStartMs) / 1000;
        long outages = quorumLossCount.get();
        if (outages == 0) return uptimeSeconds > 0 ? uptimeSeconds : -1.0;
        return (double) uptimeSeconds / outages;
    }

    /**
     * MTTR = average of all quorum-outage durations (completed + current live).
     * While an outage is in progress the current elapsed time is folded in as a
     * provisional sample so the displayed value grows in real time.
     * Returns -1 only when no outage has ever occurred (shown as "—" in UI).
     */
    private double calculateMttr() {
        double liveOutageSec = quorumLossStartMs >= 0
            ? (System.currentTimeMillis() - quorumLossStartMs) / 1000.0
            : -1.0;

        if (mttrSamples.isEmpty() && liveOutageSec < 0) return -1.0;

        java.util.List<Double> effective = new java.util.ArrayList<>(mttrSamples);
        if (liveOutageSec >= 0) effective.add(liveOutageSec);
        return effective.stream().mapToDouble(Double::doubleValue).average().orElse(-1.0);
    }

    /**
     * Availability = MTTF / (MTTF + MTTR) × 100.
     * Requires both values to be available (> 0).
     * Falls back to 100% when no outage has occurred, or "—" when
     * MTTF is known but MTTR has not been measured yet.
     */
    private double calculateAvailability(double mttf, double mttr) {
        if (mttf < 0) return 100.0;   // no outages at all — fully available
        if (mttr < 0) return -1.0;    // outage happened but not yet recovered
        double denom = mttf + mttr;
        if (denom == 0) return 100.0;
        return (mttf / denom) * 100.0;
    }

    /**
     * Human-readable availability label.
     */
    private String availabilityLabel(double availPct) {
        if (availPct < 0)       return "Outage in progress";
        if (availPct >= 99.999) return "Five Nines (99.999%)";
        if (availPct >= 99.99)  return "Four Nines (99.99%)";
        if (availPct >= 99.9)   return "Three Nines (99.9%)";
        if (availPct >= 99.0)   return "Two Nines (99%)";
        if (availPct >= 95.0)   return "High Availability (95%)";
        if (availPct >= 80.0)   return String.format("Degraded (%.1f%%)", availPct);
        return String.format("Critical (%.1f%%)", availPct);
    }

    /**
     * Extract recent failures from node health data.
     */
    private List<String> extractRecentFailures(Map<String, NodeHealth> nodeHealthMap) {
        LocalDateTime oneMinuteAgo = LocalDateTime.now().minusMinutes(1);
        
        return nodeHealthMap.values().stream()
            .filter(health -> !health.isAlive() &&
                    health.getStatusTimestamp() != null &&
                    health.getStatusTimestamp().isAfter(oneMinuteAgo))
            .map(health -> health.getNodeId() + " (" + health.getFailureReason() + ")")
            .collect(Collectors.toList());
    }
}
