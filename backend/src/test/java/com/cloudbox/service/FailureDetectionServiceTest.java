package com.cloudbox.service;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cloudbox.model.NodeHealth;

/**
 * Unit tests for FailureDetectionService implementation.
 */
@ExtendWith(MockitoExtension.class)
class FailureDetectionServiceTest {
    
    @Mock
    private HeartbeatMonitor heartbeatMonitor;
    
    private FailureDetectionService failureDetectionService;
    
    @BeforeEach
    void setUp() {
        failureDetectionService = new FailureDetectionServiceImpl(heartbeatMonitor);
    }
    
    @Test
    void testInitializeCreatesHealthStatusForAllNodes() {
        failureDetectionService.initialize();
        
        Map<String, NodeHealth> healthMap = failureDetectionService.getAllNodeHealth();
        assertEquals(5, healthMap.size());
    }
    
    @Test
    void testAllNodesInitiallyHealthy() {
        failureDetectionService.initialize();
        
        List<String> healthyNodes = failureDetectionService.getHealthyNodes();
        assertEquals(5, healthyNodes.size());
    }
    
    @Test
    void testNoFailedNodesInitially() {
        failureDetectionService.initialize();
        
        List<String> failedNodes = failureDetectionService.getFailedNodes();
        assertTrue(failedNodes.isEmpty());
    }
    
    @Test
    void testGetNodeHealthReturnsValidStatus() {
        failureDetectionService.initialize();
        
        NodeHealth health = failureDetectionService.getNodeHealth("node-1");
        assertNotNull(health);
        assertEquals("node-1", health.getNodeId());
        assertTrue(health.isAlive());
        assertEquals("HEALTHY", health.getStatus());
    }
    
    @Test
    void testHasQuorumReturnsTrueWithAllNodesHealthy() {
        failureDetectionService.initialize();
        
        assertTrue(failureDetectionService.hasQuorum());
    }
    
    @Test
    void testGetHealthyNodeCountReturnsCorrectCount() {
        failureDetectionService.initialize();
        
        assertEquals(5, failureDetectionService.getHealthyNodeCount());
    }
    
    @Test
    void testMarkNodeAsFailedChangesStatus() {
        failureDetectionService.initialize();
        
        failureDetectionService.markNodeAsFailed("node-1", "Network timeout");
        
        NodeHealth health = failureDetectionService.getNodeHealth("node-1");
        assertFalse(health.isAlive());
        assertEquals("UNHEALTHY", health.getStatus());
        assertEquals("Network timeout", health.getFailureReason());
    }
    
    @Test
    void testClearFailureStatusRestoresNode() {
        failureDetectionService.initialize();
        failureDetectionService.markNodeAsFailed("node-1", "Test failure");
        
        failureDetectionService.clearFailureStatus("node-1");
        
        NodeHealth health = failureDetectionService.getNodeHealth("node-1");
        assertTrue(health.isAlive());
        assertEquals("HEALTHY", health.getStatus());
        assertNull(health.getFailureReason());
    }
    
    @Test
    void testIsNodeUnhealthyReturnsTrueForFailedNode() {
        failureDetectionService.initialize();
        failureDetectionService.markNodeAsFailed("node-1", "Test");
        
        assertTrue(failureDetectionService.isNodeUnhealthy("node-1"));
    }
}
