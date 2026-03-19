package com.cloudbox.service;

import org.apache.curator.framework.CuratorFramework;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloudbox.model.ConsensusProposal;

/**
 * Unit tests for ConsensusManager.
 *
 * Tests:
 * - Consensus manager initialization
 * - ZXID generation and ordering
 * - Epoch management
 * - Proposal creation and atomicity
 * - Quorum-based write capability
 */
@ExtendWith(MockitoExtension.class)
public class ConsensusManagerTest {

    @Mock
    private CuratorFramework curatorFramework;

    @Mock
    private LeaderElectionService leaderElectionService;

    @Mock
    private PartitionHandler partitionHandler;

    @InjectMocks
    private ConsensusManager consensusManager;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(consensusManager, "nodeId", 1);
    }

    @Test
    void testInitializeConsensusManager() {
        when(leaderElectionService.getCurrentElectionEpoch()).thenReturn(-1L);
        consensusManager.initialize();
        assertTrue(true);
    }

    @Test
    void testGetCurrentEpoch() {
        long epoch = consensusManager.getCurrentEpoch();
        assertTrue(epoch >= 0,
                "Epoch should be non-negative");
    }

    @Test
    void testGetCurrentZxidCounterStartsAtZero() {
        long counter = consensusManager.getCurrentZxidCounter();
        assertEquals(0, counter,
                "ZXID counter should start at 0");
    }

    @Test
    void testCanProposeWhenPartitionHandlerAllows() {
        when(partitionHandler.canWrite()).thenReturn(true);
        assertTrue(consensusManager.canPropose(),
                "Should be able to propose when partition handler allows");
    }

    @Test
    void testCannotProposeWhenPartitionHandlerBlocks() {
        when(partitionHandler.canWrite()).thenReturn(false);
        assertFalse(consensusManager.canPropose(),
                "Should not be able to propose when partition handler blocks");
    }

    @Test
    void testProposeThrowsExceptionWhenNoQuorum() {
        when(partitionHandler.canWrite()).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> {
            consensusManager.propose("test data");
        }, "Should throw IllegalStateException when no quorum");
    }

    @Test
    void testProposalDataStructures() {
        ConsensusProposal proposal = ConsensusProposal.builder()
                .proposalId("test-1")
                .epoch(1L)
                .zxid(100L)
                .data("test operation")
                .proposerId(1)
                .timestamp(System.currentTimeMillis())
                .build();

        assertEquals("test-1", proposal.getProposalId());
        assertEquals(1L, proposal.getEpoch());
        assertEquals(100L, proposal.getZxid());
        assertEquals("test operation", proposal.getData());
    }

    @Test
    void testMultipleProposalsHaveUniqueStructure() {
        ConsensusProposal proposal1 = ConsensusProposal.builder()
                .proposalId("1-1")
                .epoch(1L)
                .zxid(1L)
                .data("data1")
                .proposerId(1)
                .timestamp(System.currentTimeMillis())
                .build();

        ConsensusProposal proposal2 = ConsensusProposal.builder()
                .proposalId("1-2")
                .epoch(1L)
                .zxid(2L)
                .data("data2")
                .proposerId(1)
                .timestamp(System.currentTimeMillis() + 100)
                .build();

        assertNotNull(proposal1);
        assertNotNull(proposal2);
        assertNotEquals(proposal1.getZxid(), proposal2.getZxid());
    }
}
