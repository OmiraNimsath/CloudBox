package com.cloudbox.model;

import java.util.List;
import java.util.Map;

/**
 * Full cluster fault-tolerance status returned to the dashboard.
 */
public record FaultStatus(
        String clusterState,             // HEALTHY | DEGRADED | CRITICAL
        int totalNodes,
        int healthyNodes,
        int failedNodes,
        boolean hasQuorum,
        double mttfSeconds,              // Mean Time To Failure
        double mttrSeconds,              // Mean Time To Repair
        double availabilityPercentage,
        int underReplicatedFiles,
        Map<Integer, NodeStatus> nodeHealthMap,
        List<String> recentFailures
) {}
