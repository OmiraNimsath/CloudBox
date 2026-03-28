package com.cloudbox.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aggregates the overall fault tolerance status of the cluster.
 * Shows node health, recovery tasks, and redundancy metrics.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FaultStatus {
    private LocalDateTime timestamp;
    private String clusterState; // HEALTHY, DEGRADED, CRITICAL, PARTITIONED
    private int totalNodes;
    private int healthyNodes;
    private int failedNodes;
    private int recoveringNodes;
    private Map<String, NodeHealth> nodeHealthMap;
    private List<RecoveryTask> activeRecoveryTasks;
    private List<RecoveryTask> completedRecoveryTasks;
    private int underReplicatedFileCount;
    private double averageReplicationFactor;
    private boolean hasQuorum;
    private String lastHeartbeatTime;
    private String failureDetectionStatus; // ENABLED, DISABLED, ERROR
    private List<String> recentFailures; // list of recently detected failures

    // Reliability metrics (Lecture 4 — Fault Tolerance)
    private double mttfSeconds;           // Mean Time To Failure: avg uptime between failures
    private double mttrSeconds;           // Mean Time To Repair: avg completed recovery duration
    private double availabilityPercentage; // A = MTTF / (MTTF + MTTR) × 100
    private String availabilityLabel;     // e.g. "Four Nines (99.99%)"
}
