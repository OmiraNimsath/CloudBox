package com.cloudbox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cloudbox.model.FaultStatus;
import com.cloudbox.model.NodeHealth;

/**
 * End-to-end integration tests for Fault Tolerance system.
 */
@ExtendWith(MockitoExtension.class)
class FaultToleranceIntegrationTest {
    
    @Mock
    private HeartbeatMonitor heartbeatMonitor;
    
    @Mock
    private StorageModulePort storageModulePort;
    
    private FailureDetectionService failureDetectionService;
    private RecoveryManager recoveryManager;
    private FaultToleranceManager faultToleranceManager;
    
    @BeforeEach
    void setUp() {
        failureDetectionService = new FailureDetectionServiceImpl(heartbeatMonitor);
        recoveryManager = new RecoveryManagerImpl(failureDetectionService, storageModulePort);
        faultToleranceManager = new FaultToleranceManagerImpl(
            heartbeatMonitor,
            failureDetectionService,
            recoveryManager
        );
    }
    
    @Test
    void testSystemInitializesSuccessfully() {
        faultToleranceManager.initialize();
        
        assertTrue(faultToleranceManager.isInitialized());
        assertTrue(faultToleranceManager.isEnabled());
    }
    
    @Test
    void testClusterHealthyStateWhenAllNodesUp() {
        faultToleranceManager.initialize();
        
        FaultStatus status = faultToleranceManager.getFaultStatus();
        
        assertEquals("HEALTHY", status.getClusterState());
        assertEquals(5, status.getHealthyNodes());
        assertTrue(status.isHasQuorum());
    }
    
    @Test
    void testClusterDegradedStateWhenSomeNodesFail() {
        faultToleranceManager.initialize();
        failureDetectionService.markNodeAsFailed("node-1", "Network failure");
        failureDetectionService.markNodeAsFailed("node-2", "Crash");
        
        FaultStatus status = faultToleranceManager.getFaultStatus();
        
        assertEquals("DEGRADED", status.getClusterState());
        assertEquals(3, status.getHealthyNodes());
        assertEquals(2, status.getFailedNodes());
        assertTrue(status.isHasQuorum());
    }
    
    @Test
    void testQuorumLostWhenMajorityFails() {
        faultToleranceManager.initialize();
        failureDetectionService.markNodeAsFailed("node-1", "Failure");
        failureDetectionService.markNodeAsFailed("node-2", "Failure");
        failureDetectionService.markNodeAsFailed("node-3", "Failure");
        
        FaultStatus status = faultToleranceManager.getFaultStatus();
        
        assertEquals("CRITICAL", status.getClusterState());
        assertEquals(2, status.getHealthyNodes());
        assertFalse(status.isHasQuorum());
    }
    
    @Test
    void testRecoveryProcessForFailedNode() {
        when(failureDetectionService.getHealthyNodes()).thenReturn(
            java.util.Arrays.asList("node-2", "node-3", "node-4")
        );
        when(heartbeatMonitor.isNodeAlive("node-1")).thenReturn(false);
        
        faultToleranceManager.initialize();
        failureDetectionService.markNodeAsFailed("node-1", "Test failure");
        
        String recoveryId = recoveryManager.initiateRecovery("node-1");
        assertNotNull(recoveryId);
        
        failureDetectionService.clearFailureStatus("node-1");
        NodeHealth health = failureDetectionService.getNodeHealth("node-1");
        assertTrue(health.isAlive());
    }
    
    @Test
    void testSystemCanBeDisabledAndReEnabled() {
        faultToleranceManager.initialize();
        assertTrue(faultToleranceManager.isEnabled());
        
        faultToleranceManager.disable();
        assertFalse(faultToleranceManager.isEnabled());
        
        faultToleranceManager.enable();
        assertTrue(faultToleranceManager.isEnabled());
    }
    
    @Test
    void testSystemShutdownCleansUp() {
        faultToleranceManager.initialize();
        assertTrue(faultToleranceManager.isInitialized());
        
        faultToleranceManager.shutdown();
        assertFalse(faultToleranceManager.isInitialized());
    }
}
