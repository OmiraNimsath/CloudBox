package com.cloudbox.service;

import org.apache.curator.framework.CuratorFramework;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloudbox.model.LeaderInfo;

/**
 * Unit tests for LeaderElectionService.
 *
 * Tests:
 * - Leader election initialization
 * - Current leader tracking
 * - Leadership status
 * - Election epoch management
 */
@ExtendWith(MockitoExtension.class)
public class LeaderElectionServiceTest {

    @Mock
    private CuratorFramework curatorFramework;

    @InjectMocks
    private LeaderElectionService leaderElectionService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(leaderElectionService, "nodeId", 1);
        ReflectionTestUtils.setField(leaderElectionService, "leaderElectionTimeout", 10000L);
        ReflectionTestUtils.setField(leaderElectionService, "heartbeatInterval", 3000L);
    }

    @Test
    void testIsCurrentLeaderInitiallyFalse() {
        assertFalse(leaderElectionService.isCurrentLeader(),
                "Node should not be leader initially");
    }

    @Test
    void testGetCurrentLeaderInitiallyNull() {
        assertNull(leaderElectionService.getCurrentLeader(),
                "Current leader should be null initially");
    }

    @Test
    void testGetCurrentElectionEpochInitiallyNegative() {
        long epoch = leaderElectionService.getCurrentElectionEpoch();
        assertEquals(-1, epoch,
                "Election epoch should be -1 when no leader elected");
    }

    @Test
    void testLeaderInfoCanBeSet() {
        LeaderInfo leaderInfo = LeaderInfo.builder()
                .leaderId(1)
                .electionEpoch(1000L)
                .zxid(12345L)
                .lastHeartbeat(System.currentTimeMillis())
                .alive(true)
                .build();

        ReflectionTestUtils.setField(leaderElectionService, "currentLeader",
                new java.util.concurrent.atomic.AtomicReference<>(leaderInfo));

        LeaderInfo retrieved = leaderElectionService.getCurrentLeader();
        assertNotNull(retrieved);
        assertEquals(1, retrieved.getLeaderId());
        assertEquals(1000L, retrieved.getElectionEpoch());
        assertTrue(retrieved.isAlive());
    }

    @Test
    void testStopLeaderElectionWithoutStarting() {
        // Should not throw exception when stopping without starting
        assertDoesNotThrow(() -> leaderElectionService.stopLeaderElection());
    }
}
