package com.cloudbox.service.replication;

import java.util.List;

import com.cloudbox.model.NodeStatus;

/**
 * Port for consensus and cluster membership lookups.
 */
public interface ConsensusModulePort {

    int getCurrentLeaderNodeId();

    List<NodeStatus> getClusterNodes();
}
