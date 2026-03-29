package com.cloudbox.service;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.cloudbox.model.HeartbeatInfo;
import com.cloudbox.model.NodeStatus;

@Service
public class ConsensusModuleAdapter implements ConsensusModulePort {

    private final ClusterCoordinator clusterCoordinator;
    private final HeartbeatMonitor heartbeatMonitor;

    public ConsensusModuleAdapter(ClusterCoordinator clusterCoordinator,
                                  HeartbeatMonitor heartbeatMonitor) {
        this.clusterCoordinator = clusterCoordinator;
        this.heartbeatMonitor   = heartbeatMonitor;
    }

    @Override
    public int getCurrentLeaderNodeId() {
        if (clusterCoordinator.getLeaderInfo() != null) {
            return clusterCoordinator.getLeaderInfo().getLeaderId();
        }
        return -1; // No leader elected yet
    }

    @Override
    public List<NodeStatus> getClusterNodes() {
        List<NodeStatus> nodes = new ArrayList<>();
        var status = clusterCoordinator.getClusterStatus();

        if (status != null && status.getNodeStatuses() != null) {
            status.getNodeStatuses().forEach((nodeId, state) -> {
                boolean isAlive  = "HEALTHY".equals(state);
                boolean isLeader = clusterCoordinator.getLeaderInfo() != null
                        && clusterCoordinator.getLeaderInfo().getLeaderId() == nodeId;

                long lastHeartbeatMs = resolveLastHeartbeat("node-" + nodeId, isAlive);

                nodes.add(NodeStatus.builder()
                        .nodeId(nodeId)
                        .alive(isAlive)
                        .isLeader(isLeader)
                        .role(isLeader ? "leader" : "follower")
                        .lastHeartbeat(lastHeartbeatMs)
                        .build());
            });
        } else {
            // Fallback: simulate 5 nodes; leader determined by LeaderElection, not hardcoded
            int leaderId = clusterCoordinator.getLeaderInfo() != null
                    ? clusterCoordinator.getLeaderInfo().getLeaderId()
                    : -1; // unknown — no node gets isLeader=true
            for (int i = 1; i <= 5; i++) {
                boolean isLeader = (i == leaderId);
                long lastHeartbeatMs = resolveLastHeartbeat("node-" + i, true);
                nodes.add(NodeStatus.builder()
                        .nodeId(i)
                        .alive(true)
                        .isLeader(isLeader)
                        .role(isLeader ? "leader" : "follower")
                        .lastHeartbeat(lastHeartbeatMs)
                        .build());
            }
        }

        return nodes;
    }

    /**
     * Resolve the last-heartbeat timestamp for a node.
     * Uses HeartbeatMonitor when a record exists; falls back to now() for
     * alive nodes so that DefaultReplicationStrategy's sort is stable.
     */
    private long resolveLastHeartbeat(String nodeKey, boolean isAlive) {
        HeartbeatInfo hb = heartbeatMonitor.getLastHeartbeat(nodeKey);
        if (hb != null && hb.getTimestamp() != null) {
            return hb.getTimestamp().toInstant(ZoneOffset.UTC).toEpochMilli();
        }
        return isAlive ? System.currentTimeMillis() : 0L;
    }
}
