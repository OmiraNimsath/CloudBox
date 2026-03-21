package com.cloudbox.service.replication;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mockito;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudbox.config.ReplicationProperties;
import com.cloudbox.domain.replication.ConsistencyModel;
import com.cloudbox.domain.replication.ReplicaSelection;
import com.cloudbox.model.NodeStatus;

class QuorumWriteManagerTest {

    private ReplicationStrategy replicationStrategy;
    private ConsensusModulePort consensusModulePort;
    private FaultTolerancePort faultTolerancePort;
    private StorageModulePort storageModulePort;
    private TimeSyncPort timeSyncPort;

    private QuorumWriteManager quorumWriteManager;

    @BeforeEach
    void setUp() {
        replicationStrategy = Mockito.mock(ReplicationStrategy.class);
        consensusModulePort = Mockito.mock(ConsensusModulePort.class);
        faultTolerancePort = Mockito.mock(FaultTolerancePort.class);
        storageModulePort = Mockito.mock(StorageModulePort.class);
        timeSyncPort = Mockito.mock(TimeSyncPort.class);

        ReplicationProperties replicationProperties = new ReplicationProperties();
        replicationProperties.setQuorumSize(3);
        replicationProperties.setReplicationFactor(5);
        replicationProperties.setRetryCount(3);
        replicationProperties.setWriteTimeout(Duration.ofSeconds(1));

        quorumWriteManager = new QuorumWriteManager(
                replicationStrategy,
                replicationProperties,
                consensusModulePort,
                faultTolerancePort,
                storageModulePort,
                timeSyncPort);
    }

    @Test
    void shouldSucceedWhenQuorumReached() throws Exception {
        when(consensusModulePort.getCurrentLeaderNodeId()).thenReturn(1);
        when(consensusModulePort.getClusterNodes()).thenReturn(List.of(NodeStatus.builder().nodeId(1).alive(true).build()));
        when(timeSyncPort.currentLogicalTimestamp()).thenReturn(100L);

        ReplicaSelection selection = new ReplicaSelection(
                ConsistencyModel.QUORUM_WRITE_LEADER_READ,
                List.of(1, 2, 3, 4, 5),
                3);
        when(replicationStrategy.selectWriteReplicas(eq(1), any())).thenReturn(selection);
        when(faultTolerancePort.isNodeWritable(any(Integer.class))).thenReturn(true);

        QuorumWriteResult result = quorumWriteManager.replicateWrite("file-a", "payload".getBytes());

        assertEquals(3, result.requiredAcknowledgements());
        assertEquals(5, result.acknowledgedNodeIds().size());
        assertEquals(1, result.attempts());
        verify(storageModulePort, times(5)).persistReplica(any(Integer.class), eq("file-a"), any(byte[].class), eq(100L));
    }

    @Test
    void shouldRetryAndFailWhenQuorumCannotBeReached() throws Exception {
        when(consensusModulePort.getCurrentLeaderNodeId()).thenReturn(1);
        when(consensusModulePort.getClusterNodes()).thenReturn(List.of(NodeStatus.builder().nodeId(1).alive(true).build()));
        when(timeSyncPort.currentLogicalTimestamp()).thenReturn(200L);

        ReplicaSelection selection = new ReplicaSelection(
                ConsistencyModel.QUORUM_WRITE_LEADER_READ,
                List.of(1, 2, 3, 4, 5),
                3);
        when(replicationStrategy.selectWriteReplicas(eq(1), any())).thenReturn(selection);

        when(faultTolerancePort.isNodeWritable(1)).thenReturn(true);
        when(faultTolerancePort.isNodeWritable(2)).thenReturn(true);
        when(faultTolerancePort.isNodeWritable(3)).thenReturn(false);
        when(faultTolerancePort.isNodeWritable(4)).thenReturn(false);
        when(faultTolerancePort.isNodeWritable(5)).thenReturn(false);

        doThrow(new RuntimeException("disk failure"))
                .when(storageModulePort)
                .persistReplica(eq(2), eq("file-b"), any(byte[].class), eq(200L));

        assertThrows(QuorumWriteException.class,
                () -> quorumWriteManager.replicateWrite("file-b", "payload".getBytes()));

        verify(storageModulePort, times(4)).persistReplica(eq(1), eq("file-b"), any(byte[].class), eq(200L));
        verify(storageModulePort, times(4)).persistReplica(eq(2), eq("file-b"), any(byte[].class), eq(200L));
        verify(faultTolerancePort, times(4)).recordReplicationFailure(eq(2), any(Exception.class));
    }
}
