package com.cloudbox.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import com.cloudbox.model.RecoveryTask;

import org.springframework.stereotype.Service;

import com.cloudbox.model.FaultStatus;
import com.cloudbox.model.NodeHealth;

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
    /** Running count of node failures detected since startup. */
    private final AtomicLong totalFailureCount = new AtomicLong(0);

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
        
        // Aggregate data
        FaultStatus status = FaultStatus.builder()
            .timestamp(LocalDateTime.now())
            .clusterState(clusterState)
            .totalNodes(totalNodes)
            .healthyNodes(healthyCount)
            .failedNodes(failedCount)
            .recoveringNodes(getRecoveringNodeCount(nodeHealthMap))
            .nodeHealthMap(nodeHealthMap)
            .activeRecoveryTasks(recoveryManager.getActiveRecoveryTasks())
            .completedRecoveryTasks(recoveryManager.getCompletedRecoveryTasks())
            .underReplicatedFileCount(recoveryManager.getUnderReplicatedFileCount())
            .averageReplicationFactor(calculateAverageReplicationFactor(nodeHealthMap))
            .hasQuorum(failureDetectionService.hasQuorum())
            .lastHeartbeatTime(getLastHeartbeatTime())
            .failureDetectionStatus(getFailureDetectionStatus())
            .recentFailures(extractRecentFailures(nodeHealthMap))
            .mttfSeconds(calculateMttf(failedCount))
            .mttrSeconds(calculateMttr(recoveryManager.getCompletedRecoveryTasks()))
            .availabilityPercentage(calculateAvailability(calculateMttf(failedCount), calculateMttr(recoveryManager.getCompletedRecoveryTasks())))
            .availabilityLabel(availabilityLabel(calculateAvailability(calculateMttf(failedCount), calculateMttr(recoveryManager.getCompletedRecoveryTasks()))))
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
        if (healthyCount == totalNodes) {
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
     * Calculate average replication factor across all nodes.
     */
    private double calculateAverageReplicationFactor(Map<String, NodeHealth> nodeHealthMap) {
        if (nodeHealthMap.isEmpty()) return 0.0;
        
        return nodeHealthMap.values().stream()
            .mapToInt(NodeHealth::getHealthyReplicaCount)
            .average()
            .orElse(0.0);
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
     * MTTF = total uptime / number of failures observed.
     * Returns uptime in seconds when no failures have occurred yet.
     */
    private double calculateMttf(int currentFailedCount) {
        long uptimeSeconds = (System.currentTimeMillis() - (systemStartMs == 0 ? System.currentTimeMillis() : systemStartMs)) / 1000;
        long failures = totalFailureCount.updateAndGet(prev -> Math.max(prev, currentFailedCount));
        if (failures == 0) return uptimeSeconds > 0 ? uptimeSeconds : 3600.0;
        return (double) uptimeSeconds / failures;
    }

    /**
     * MTTR = average duration of completed recovery tasks in seconds.
     */
    private double calculateMttr(List<RecoveryTask> completedTasks) {
        return completedTasks.stream()
            .filter(t -> t.getStartedAt() != null && t.getCompletedAt() != null)
            .mapToLong(t -> ChronoUnit.SECONDS.between(t.getStartedAt(), t.getCompletedAt()))
            .average()
            .orElse(30.0); // default 30 s estimate when no tasks completed yet
    }

    /**
     * Steady-state availability: A = MTTF / (MTTF + MTTR) × 100.
     */
    private double calculateAvailability(double mttf, double mttr) {
        double denom = mttf + mttr;
        if (denom == 0) return 100.0;
        return (mttf / denom) * 100.0;
    }

    /**
     * Human-readable availability tier label.
     */
    private String availabilityLabel(double availPct) {
        if (availPct >= 99.999) return "Five Nines (99.999%)";
        if (availPct >= 99.99)  return "Four Nines (99.99%)";
        if (availPct >= 99.9)   return "Three Nines (99.9%)";
        if (availPct >= 99.0)   return "Two Nines (99%)";
        if (availPct >= 95.0)   return "High Availability (95%)";
        return String.format("Degraded (%.1f%%)", availPct);
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
