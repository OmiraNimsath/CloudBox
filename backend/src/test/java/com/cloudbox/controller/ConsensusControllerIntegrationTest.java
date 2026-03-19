package com.cloudbox.controller;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cloudbox.model.ClusterStatus;
import com.cloudbox.model.ConsensusProposal;
import com.cloudbox.model.LeaderInfo;
import com.cloudbox.model.PartitionStatus;
import com.cloudbox.service.ClusterCoordinator;

/**
 * Unit tests for ConsensusController.
 *
 * Tests all consensus endpoints using mocked services
 */
@ExtendWith(MockitoExtension.class)
public class ConsensusControllerIntegrationTest {

    @Mock
    private ClusterCoordinator clusterCoordinator;

    @InjectMocks
    private ConsensusController consensusController;

    @BeforeEach
    void setUp() {
        when(clusterCoordinator.getNodeId()).thenReturn(1);
    }

    @Test
    void testGetClusterStatusReturnsValidResponse() {
        Set<Integer> activeNodes = new HashSet<>();
        activeNodes.add(1);
        activeNodes.add(2);
        activeNodes.add(3);

        LeaderInfo leader = LeaderInfo.builder()
                .leaderId(1)
                .electionEpoch(1000L)
                .alive(true)
                .build();

        PartitionStatus partition = PartitionStatus.builder()
                .partitioned(false)
                .reachableNodes(5)
                .canWrite(true)
                .responseNodes(activeNodes)
                .build();

        ClusterStatus status = ClusterStatus.builder()
                .clusterHealthy(true)
                .activeNodes(activeNodes)
                .leader(leader)
                .partitionStatus(partition)
                .timestamp(System.currentTimeMillis())
                .build();

        when(clusterCoordinator.getClusterStatus()).thenReturn(status);

        var response = consensusController.getClusterStatus();

        assertNotNull(response);
        assertTrue(response.getBody().isSuccess());
        assertEquals(leader, response.getBody().getData().getLeader());
        assertTrue(response.getBody().getData().isClusterHealthy());
    }

    @Test
    void testGetLeaderInfoReturnsValidLeader() {
        LeaderInfo leader = LeaderInfo.builder()
                .leaderId(1)
                .electionEpoch(1000L)
                .zxid(12345L)
                .alive(true)
                .build();

        when(clusterCoordinator.getLeaderInfo()).thenReturn(leader);

        var response = consensusController.getLeaderInfo();

        assertNotNull(response);
        assertTrue(response.getBody().isSuccess());
        assertEquals(1, response.getBody().getData().getLeaderId());
        assertEquals(1000L, response.getBody().getData().getElectionEpoch());
    }

    @Test
    void testGetLeaderInfoReturns404WhenNoLeader() {
        when(clusterCoordinator.getLeaderInfo()).thenReturn(null);

        var response = consensusController.getLeaderInfo();

        assertNotNull(response);
        assertFalse(response.getBody().isSuccess());
    }

    @Test
    void testGetPartitionStatusReturnsValidStatus() {
        Set<Integer> responseNodes = new HashSet<>();
        responseNodes.add(1);
        responseNodes.add(2);
        responseNodes.add(3);

        PartitionStatus partition = PartitionStatus.builder()
                .partitioned(false)
                .reachableNodes(5)
                .canWrite(true)
                .responseNodes(responseNodes)
                .detectionTime(System.currentTimeMillis())
                .partitionDescription("Healthy")
                .build();

        when(clusterCoordinator.getPartitionStatus()).thenReturn(partition);

        var response = consensusController.getPartitionStatus();

        assertNotNull(response);
        assertTrue(response.getBody().isSuccess());
        assertFalse(response.getBody().getData().isPartitioned());
        assertTrue(response.getBody().getData().isCanWrite());
        assertEquals(5, response.getBody().getData().getReachableNodes());
    }

    @Test
    void testProposeCreatesValidProposal() throws Exception {
        ConsensusProposal proposal = ConsensusProposal.builder()
                .proposalId("1-100")
                .epoch(1L)
                .zxid(100L)
                .data("test operation")
                .proposerId(1)
                .timestamp(System.currentTimeMillis())
                .build();

        when(clusterCoordinator.canWrite()).thenReturn(true);
        when(clusterCoordinator.propose("test operation")).thenReturn(proposal);

        Map<String, String> request = new HashMap<>();
        request.put("data", "test operation");

        var response = consensusController.propose(request);

        assertNotNull(response);
        assertTrue(response.getBody().isSuccess());
        assertEquals("1-100", response.getBody().getData().getProposalId());
        assertEquals("test operation", response.getBody().getData().getData());
    }

    @Test
    void testProposeMissingDataReturnsError() {
        Map<String, String> request = new HashMap<>();

        var response = consensusController.propose(request);

        assertNotNull(response);
        assertFalse(response.getBody().isSuccess());
    }

    @Test
    void testProposePartitionedReturnsUnavailable() {
        when(clusterCoordinator.canWrite()).thenReturn(false);

        Map<String, String> request = new HashMap<>();
        request.put("data", "test");

        var response = consensusController.propose(request);

        assertNotNull(response);
        assertFalse(response.getBody().isSuccess());
    }

    @Test
    void testHeartbeatReturnsValidData() {
        when(clusterCoordinator.isLeader()).thenReturn(true);
        when(clusterCoordinator.canWrite()).thenReturn(true);

        var response = consensusController.heartbeat();

        assertNotNull(response);
        assertTrue(response.getBody().isSuccess());
        assertEquals(1, (Integer) response.getBody().getData().get("nodeId"));
        assertTrue((Boolean) response.getBody().getData().get("isLeader"));
    }

    @Test
    void testIsLeaderReturnsCorrectStatus() {
        when(clusterCoordinator.isLeader()).thenReturn(true);

        var response = consensusController.isLeader();

        assertNotNull(response);
        assertTrue((Boolean) response.getBody().getData().get("isLeader"));
    }

    @Test
    void testCanWriteReturnsCorrectStatus() {
        Set<Integer> responseNodes = new HashSet<>();
        responseNodes.add(1);
        responseNodes.add( 2);
        responseNodes.add(3);

        PartitionStatus partition = PartitionStatus.builder()
                .canWrite(true)
                .reachableNodes(3)
                .partitioned(false)
                .responseNodes(responseNodes)
                .build();

        when(clusterCoordinator.canWrite()).thenReturn(true);
        when(clusterCoordinator.getPartitionStatus()).thenReturn(partition);

        var response = consensusController.canWrite();

        assertNotNull(response);
        assertTrue(response.getBody().isSuccess());
        assertTrue((Boolean) response.getBody().getData().get("canWrite"));
        assertEquals(3, response.getBody().getData().get("reachableNodes"));
    }

    @Test
    void testHealthReturnsValidData() {
        Set<Integer> activeNodes = new HashSet<>();
        activeNodes.add(1);
        activeNodes.add(2);
        activeNodes.add(3);

        LeaderInfo leader = LeaderInfo.builder()
                .leaderId(1)
                .electionEpoch(1000L)
                .alive(true)
                .build();

        PartitionStatus partition = PartitionStatus.builder()
                .partitioned(false)
                .reachableNodes(5)
                .canWrite(true)
                .responseNodes(activeNodes)
                .build();

        ClusterStatus status = ClusterStatus.builder()
                .clusterHealthy(true)
                .activeNodes(activeNodes)
                .leader(leader)
                .partitionStatus(partition)
                .build();

        when(clusterCoordinator.isInitialized()).thenReturn(true);
        when(clusterCoordinator.isLeader()).thenReturn(false);
        when(clusterCoordinator.canWrite()).thenReturn(true);
        when(clusterCoordinator.getClusterStatus()).thenReturn(status);

        var response = consensusController.health();

        assertNotNull(response);
        assertTrue(response.getBody().isSuccess());
        assertEquals(1, response.getBody().getData().get("nodeId"));
        assertTrue((Boolean) response.getBody().getData().get("initialized"));
    }
}
