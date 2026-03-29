package com.cloudbox.service;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class FaultToleranceModuleAdapter implements FaultTolerancePort {

    private final ClusterCoordinator clusterCoordinator;
    private final FailureDetectionService failureDetectionService;

    public FaultToleranceModuleAdapter(ClusterCoordinator clusterCoordinator,
                                       FailureDetectionService failureDetectionService) {
        this.clusterCoordinator = clusterCoordinator;
        this.failureDetectionService = failureDetectionService;
    }

    @Override
    public boolean isNodeWritable(int nodeId) {
        // Check admin-simulated failures via FailureDetectionService
        String nodeKey = "node-" + nodeId;
        if (failureDetectionService.isNodeUnhealthy(nodeKey)) {
            return false;
        }
        // Also check cluster partition status
        var status = clusterCoordinator.getClusterStatus();
        if (status != null && status.getNodeStatuses() != null) {
            String state = status.getNodeStatuses().get(nodeId);
            return "HEALTHY".equals(state);
        }
        return true; 
    }

    @Override
    public void recordReplicationFailure(int nodeId, Exception exception) {
        log.warn("Replication failed on node {} due to: {}", nodeId, exception.getMessage());
        // For actual fault tolerance integration, this could signal the heartbeats to mark it unreachable
    }
}