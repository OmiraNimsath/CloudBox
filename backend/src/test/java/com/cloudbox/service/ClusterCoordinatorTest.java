package com.cloudbox.service;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.anyString;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloudbox.model.ClusterStatus;
import com.cloudbox.model.LeaderInfo;
import com.cloudbox.model.PartitionStatus;

/**
 * Unit tests for ClusterCoordinator.
 *
 * Tests:
 * - Coordinator initialization and shutdown
 * - Service orchestration
 * - Unified cluster status
 * - Leadership queries
 * - Write capability checks
 */
@ExtendWith(MockitoExtension.class)
public class ClusterCoordinatorTest {

    @Mock
    private LeaderElectionService leaderElectionService;

    @Mock
    private PartitionHandler partitionHandler;

    @Mock
    private ConsensusManager consensusManager;

    @InjectMocks
    private ClusterCoordinator clusterCoordinator;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(clusterCoordinator, "nodeId", 1);
    }

    @Test
    void testCoordinatorNotInitializedByDefault() {
        assertFalse(clusterCoordinator.isInitialized(),
                "Coordinator should not be initialized by default");
    }

    @Test
    void testGetNodeId() {
        int nodeId = clusterCoordinator.getNodeId();
        assertEquals(1, nodeId);
    }

    @Test
    void testStartClusterCoordinationMarksInitialized() throws Exception {
        clusterCoordinator.startClusterCoordination();
        assertTrue(clusterCoordinator.isInitialized(),
                "Coordinator should be marked as initialized after starting");
    }

    @Test
    void testStartCoordinationCallsSubServices() throws Exception {
        clusterCoordinator.startClusterCoordination();

        verify(leaderElectionService).startLeaderElection();
        verify(partitionHandler).startPartitionDetection();
        verify(consensusManager).initialize();
    }

    @Test
    void testCanWriteDelegatesToPartitionHandler() {
        when(partitionHandler.canWrite()).thenReturn(true);
        assertTrue(clusterCoordinator.canWrite());

        when(partitionHandler.canWrite()).thenReturn(false);
        assertFalse(clusterCoordinator.canWrite());

        verify(partitionHandler, atLeast(2)).canWrite();
    }

    @Test
    void testIsLeaderDelegatesToLeaderElectionService() {
        when(leaderElectionService.isCurrentLeader()).thenReturn(true);
        assertTrue(clusterCoordinator.isLeader());

        when(leaderElectionService.isCurrentLeader()).thenReturn(false);
        assertFalse(clusterCoordinator.isLeader());

        verify(leaderElectionService, atLeast(2)).isCurrentLeader();
    }

    @Test
    void testGetLeaderInfoDelegatesToLeaderElectionService() {
        LeaderInfo expectedLeader = LeaderInfo.builder()
                .leaderId(1)
                .electionEpoch(1000L)
                .alive(true)
                .build();

        when(leaderElectionService.getCurrentLeader()).thenReturn(expectedLeader);
        LeaderInfo retrieved = clusterCoordinator.getLeaderInfo();

        assertEquals(expectedLeader, retrieved);
        verify(leaderElectionService).getCurrentLeader();
    }

    @Test
    void testGetPartitionStatusDelegatesToPartitionHandler() {
        PartitionStatus expectedStatus = PartitionStatus.builder()
                .partitioned(false)
                .reachableNodes(5)
                .canWrite(true)
                .build();

        when(partitionHandler.getPartitionStatus()).thenReturn(expectedStatus);
        PartitionStatus retrieved = clusterCoordinator.getPartitionStatus();

        assertEquals(expectedStatus, retrieved);
        verify(partitionHandler).getPartitionStatus();
    }

    @Test
    void testGetClusterStatusReturnsAggregatedData() {
        LeaderInfo leader = LeaderInfo.builder()
                .leaderId(1)
                .electionEpoch(1000L)
                .alive(true)
                .build();

        Set<Integer> activeNodes = new HashSet<>();
        activeNodes.add(1);
        activeNodes.add(2);
        activeNodes.add(3);

        PartitionStatus partition = PartitionStatus.builder()
                .partitioned(false)
                .reachableNodes(3)
                .canWrite(true)
                .responseNodes(activeNodes)
                .build();

        when(leaderElectionService.getCurrentLeader()).thenReturn(leader);
        when(partitionHandler.getPartitionStatus()).thenReturn(partition);

        ClusterStatus status = clusterCoordinator.getClusterStatus();

        assertNotNull(status);
        assertFalse(status.isClusterHealthy() == partition.isPartitioned());
        assertEquals(leader, status.getLeader());
        assertEquals(partition, status.getPartitionStatus());
    }

    @Test
    void testStopClusterCoordinationMarksNotInitialized() throws Exception {
        clusterCoordinator.startClusterCoordination();
        assertTrue(clusterCoordinator.isInitialized());

        clusterCoordinator.stopClusterCoordination();
        assertFalse(clusterCoordinator.isInitialized(),
                "Coordinator should not be initialized after stopping");
    }

    @Test
    void testStopCoordinationCallsSubServices() throws Exception {
        clusterCoordinator.startClusterCoordination();
        clusterCoordinator.stopClusterCoordination();

        verify(partitionHandler).stopPartitionDetection();
        verify(leaderElectionService).stopLeaderElection();
    }

    @Test
    void testProposeThrowsExceptionWhenNoQuorum() throws Exception {
        when(partitionHandler.canWrite()).thenReturn(false);
        when(consensusManager.propose(anyString())).thenThrow(new IllegalStateException("No quorum"));

        assertThrows(IllegalStateException.class, () -> {
            clusterCoordinator.propose("test");
        });
    }
}
