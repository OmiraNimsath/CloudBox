package com.cloudbox.service.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.cloudbox.config.ReplicationProperties;
import com.cloudbox.domain.replication.ConsistencyModel;
import com.cloudbox.domain.replication.ReplicaSelection;
import com.cloudbox.model.NodeStatus;

class DefaultReplicationStrategyTest {

    @Test
    void shouldPrioritizeLeaderAndHealthyFollowers() {
        ReplicationProperties properties = new ReplicationProperties();
        properties.setQuorumSize(3);
        properties.setReplicationFactor(5);
        properties.setRetryCount(3);
        properties.setWriteTimeout(Duration.ofSeconds(30));

        DefaultReplicationStrategy strategy = new DefaultReplicationStrategy(properties);

        List<NodeStatus> nodes = List.of(
                node(2, true, false, 100),
                node(1, true, true, 90),
                node(3, false, false, 120),
                node(4, true, false, 80),
                node(5, true, false, 110));

        ReplicaSelection selection = strategy.selectWriteReplicas(1, nodes);

        assertEquals(ConsistencyModel.QUORUM_WRITE_LEADER_READ, selection.consistencyModel());
        assertEquals(List.of(1, 5, 2, 4), selection.targetNodeIds());
        assertEquals(3, selection.requiredAcknowledgements());
    }

    @Test
    void shouldRespectReplicationFactorCap() {
        ReplicationProperties properties = new ReplicationProperties();
        properties.setQuorumSize(3);
        properties.setReplicationFactor(2);

        DefaultReplicationStrategy strategy = new DefaultReplicationStrategy(properties);

        List<NodeStatus> nodes = List.of(
                node(1, true, true, 10),
                node(2, true, false, 20),
                node(3, true, false, 30));

        ReplicaSelection selection = strategy.selectWriteReplicas(1, nodes);
        assertEquals(List.of(1, 3), selection.targetNodeIds());
    }

    private static NodeStatus node(int nodeId, boolean alive, boolean leader, long heartbeat) {
        return NodeStatus.builder()
                .nodeId(nodeId)
                .alive(alive)
                .isLeader(leader)
                .lastHeartbeat(heartbeat)
                .port(8080 + nodeId)
                .host("localhost")
                .role(leader ? "leader" : "follower")
                .build();
    }
}