package com.cloudbox.service.replication;

import java.util.List;

import com.cloudbox.model.NodeStatus;
import com.cloudbox.domain.replication.ReplicaSelection;

/**
 * Decides which replicas should receive write operations.
 */
public interface ReplicationStrategy {

    ReplicaSelection selectWriteReplicas(int leaderNodeId, List<NodeStatus> clusterNodes);
}