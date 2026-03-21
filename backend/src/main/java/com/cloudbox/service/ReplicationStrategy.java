package com.cloudbox.service;

import java.util.List;

import com.cloudbox.domain.ReplicaSelection;
import com.cloudbox.model.NodeStatus;

/**
 * Decides which replicas should receive write operations.
 */
public interface ReplicationStrategy {

    ReplicaSelection selectWriteReplicas(int leaderNodeId, List<NodeStatus> clusterNodes);
}