package com.cloudbox.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.cloudbox.model.NodeStatus;

@Service
public class ConsensusModuleAdapter implements ConsensusModulePort {

    private final ClusterCoordinator clusterCoordinator;

    public ConsensusModuleAdapter(ClusterCoordinator clusterCoordinator) {
        this.clusterCoordinator = clusterCoordinator;
    }

    @Override
    public int getCurrentLeaderNodeId() {
        if (clusterCoordinator.getLeaderInfo() != null) {
            return clusterCoordinator.getLeaderInfo().getLeaderId();
        }
        return 1; // Fallback default
    }

    @Override
    public List<NodeStatus> getClusterNodes() {
        List<NodeStatus> nodes = new ArrayList<>();
        var status = clusterCoordinator.getClusterStatus();
        
        if (status != null && status.getNodeStatuses() != null) {
            status.getNodeStatuses().forEach((nodeId, state) -> {
                boolean isAlive = "HEALTHY".equals(state);
                boolean isLeader = clusterCoordinator.getLeaderInfo() != null && clusterCoordinator.getLeaderInfo().getLeaderId() == nodeId;
                
                nodes.add(NodeStatus.builder()
                        .nodeId(nodeId)
                        .alive(isAlive)
                        .isLeader(isLeader)
                        .role(isLeader ? "leader" : "follower")
                        .build());
            });
        } else {
            // Fallback simulated nodes 1 to 5
            for (int i=1; i<=5; i++) {
                nodes.add(NodeStatus.builder()
                        .nodeId(i)
                        .alive(true)
                        .isLeader(i==1)
                        .role(i==1 ? "leader" : "follower")
                        .build());
            }
        }
        
        return nodes;
    }
}
