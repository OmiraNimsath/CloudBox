package com.cloudbox.service;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class FaultToleranceModuleAdapter implements FaultTolerancePort {

    private final ClusterCoordinator clusterCoordinator;

    public FaultToleranceModuleAdapter(ClusterCoordinator clusterCoordinator) {
        this.clusterCoordinator = clusterCoordinator;
    }

    @Override
    public boolean isNodeWritable(int nodeId) {
        // Here we can check the node status via ClusterCoordinator if we want to get specific
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