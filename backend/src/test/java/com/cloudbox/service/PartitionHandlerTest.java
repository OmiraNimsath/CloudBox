package com.cloudbox.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import com.cloudbox.config.ClusterConfig;
import com.cloudbox.model.PartitionStatus;

/**
 * Unit tests for PartitionHandler.
 *
 * Tests:
 * - Partition detection initialization
 * - Partition status tracking
 * - Write capability based on quorum
 * - Reachable node counts
 * - Partition state changes
 */
@ExtendWith(MockitoExtension.class)
public class PartitionHandlerTest {

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private PartitionHandler partitionHandler;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(partitionHandler, "nodeId", 1);
    }

    @Test
    void testGetPartitionStatusInitiallyHealthy() {
        PartitionStatus status = partitionHandler.getPartitionStatus();

        assertNotNull(status);
        assertFalse(status.isPartitioned(),
                "Initially cluster should not be partitioned");
        assertEquals(ClusterConfig.NODE_COUNT, status.getReachableNodes(),
                "Initially all nodes should be reachable");
        assertTrue(status.isCanWrite(),
                "Should be able to write when healthy");
    }

    @Test
    void testCanWriteInitiallyTrue() {
        assertTrue(partitionHandler.canWrite(),
                "Should be able to write initially");
    }

    @Test
    void testIsPartitionedInitiallyFalse() {
        assertFalse(partitionHandler.isPartitioned(),
                "Should not be partitioned initially");
    }

    @Test
    void testReachableNodeCountInitially() {
        int reachableCount = partitionHandler.getReachableNodeCount();
        assertEquals(ClusterConfig.NODE_COUNT, reachableCount,
                "All nodes should be reachable initially");
    }

    @Test
    void testPartitionStatusNotNull() {
        PartitionStatus status = partitionHandler.getPartitionStatus();
        assertNotNull(status);
        assertNotNull(status.getResponseNodes());
        assertNotNull(status.getPartitionDescription());
    }

    @Test
    void testStopPartitionDetectionWithoutStarting() {
        // Should not throw exception when stopping without starting
        assertDoesNotThrow(() -> partitionHandler.stopPartitionDetection());
    }

    @Test
    void testPartitionStatusHasValidTimestamp() {
        PartitionStatus status = partitionHandler.getPartitionStatus();
        assertTrue(status.getDetectionTime() > 0,
                "Detection time should be set");
    }

    @Test
    void testPartitionDescriptionNotEmpty() {
        PartitionStatus status = partitionHandler.getPartitionStatus();
        assertNotNull(status.getPartitionDescription());
        assertFalse(status.getPartitionDescription().isEmpty(),
                "Partition description should not be empty");
    }
}
