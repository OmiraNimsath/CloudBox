package com.cloudbox.service.replication;

import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.cloudbox.config.ReplicationProperties;
import com.cloudbox.domain.replication.ConsistencyModel;
import com.cloudbox.domain.replication.ReplicaSelection;
import com.cloudbox.model.NodeStatus;

@Service
public class DefaultReplicationStrategy implements ReplicationStrategy {

    private static final Logger log = LoggerFactory.getLogger(DefaultReplicationStrategy.class);

    private final ReplicationProperties replicationProperties;

    public DefaultReplicationStrategy(ReplicationProperties replicationProperties) {
        this.replicationProperties = replicationProperties;
    }

    @Override
    public ReplicaSelection selectWriteReplicas(int leaderNodeId, List<NodeStatus> clusterNodes) {
        List<Integer> orderedTargets = clusterNodes.stream()
                .filter(NodeStatus::isAlive)
                .sorted(Comparator
                        .comparing((NodeStatus node) -> node.getNodeId() != leaderNodeId)
                        .thenComparing(NodeStatus::getLastHeartbeat, Comparator.reverseOrder())
                        .thenComparing(NodeStatus::getNodeId))
                .limit(replicationProperties.getReplicationFactor())
                .map(NodeStatus::getNodeId)
                .toList();

        log.info("Replica selection complete: leaderNodeId={}, targets={}, requiredAcks={}",
                leaderNodeId, orderedTargets, replicationProperties.getQuorumSize());

        return new ReplicaSelection(
                ConsistencyModel.QUORUM_WRITE_LEADER_READ,
                orderedTargets,
                replicationProperties.getQuorumSize());
    }
}