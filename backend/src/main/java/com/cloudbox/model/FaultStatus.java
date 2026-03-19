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
}
