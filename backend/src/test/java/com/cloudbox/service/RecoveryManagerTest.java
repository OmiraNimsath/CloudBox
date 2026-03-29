package com.cloudbox.service;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cloudbox.model.RecoveryTask;

/**
 * Unit tests for RecoveryManager implementation.
 */
@ExtendWith(MockitoExtension.class)
class RecoveryManagerTest {
    
    @Mock
    private FailureDetectionService failureDetectionService;
    
    @Mock
    private StorageModulePort storageModulePort;
    
    private RecoveryManager recoveryManager;
    
    @BeforeEach
    void setUp() {
        recoveryManager = new RecoveryManagerImpl(failureDetectionService, storageModulePort);
        recoveryManager.initialize();
    }
    
    @Test
    void testInitializeActivatesRecoveryManager() {
        assertTrue(recoveryManager.isRecoveryEnabled());
    }
    
    @Test
    void testInitiateRecoveryCreatesTask() {
        when(failureDetectionService.getHealthyNodes())
            .thenReturn(Arrays.asList("node-2", "node-3", "node-4"));
        
        String recoveryId = recoveryManager.initiateRecovery("node-1");
        
        assertNotNull(recoveryId);
        assertTrue(recoveryId.startsWith("recovery-"));
    }
    
    @Test
    void testInitiateRecoveryReturnsNullWhenNoHealthyNodes() {
        when(failureDetectionService.getHealthyNodes())
            .thenReturn(Arrays.asList());
        
        String recoveryId = recoveryManager.initiateRecovery("node-1");
        
        assertNull(recoveryId);
    }
    
    @Test
    void testGetRecoveryTaskReturnsCreatedTask() {
        when(failureDetectionService.getHealthyNodes())
            .thenReturn(Arrays.asList("node-2", "node-3"));
        
        String recoveryId = recoveryManager.initiateRecovery("node-1");
        RecoveryTask task = recoveryManager.getRecoveryTask(recoveryId);
        
        assertNotNull(task);
        assertEquals("node-1", task.getFailedNodeId());
        assertEquals("PENDING", task.getStatus());
    }
    
    @Test
    void testGetActiveRecoveryTasksReturnsPendingTasks() {
        when(failureDetectionService.getHealthyNodes())
            .thenReturn(Arrays.asList("node-2", "node-3"));
        
        recoveryManager.initiateRecovery("node-1");
        recoveryManager.initiateRecovery("node-2");
        
        List<RecoveryTask> activeTasks = recoveryManager.getActiveRecoveryTasks();
        assertEquals(2, activeTasks.size());
    }
    
    @Test
    void testCancelRecoveryMovesTaskToCompleted() {
        when(failureDetectionService.getHealthyNodes())
            .thenReturn(Arrays.asList("node-2"));
        
        String recoveryId = recoveryManager.initiateRecovery("node-1");
        recoveryManager.cancelRecovery(recoveryId);
        
        RecoveryTask task = recoveryManager.getRecoveryTask(recoveryId);
        assertEquals("FAILED", task.getStatus());
        assertEquals("Recovery cancelled by user", task.getErrorMessage());
    }
    
    @Test
    void testRetryRecoveryResetsTask() {
        when(failureDetectionService.getHealthyNodes())
            .thenReturn(Arrays.asList("node-2"));
        
        String recoveryId = recoveryManager.initiateRecovery("node-1");
        recoveryManager.cancelRecovery(recoveryId);
        recoveryManager.retryRecovery(recoveryId);
        
        RecoveryTask task = recoveryManager.getRecoveryTask(recoveryId);
        assertEquals("PENDING", task.getStatus());
        assertEquals(1, task.getRetryCount());
    }
    
    @Test
    void testGetRecoveryProgressReturnsZeroForNewTask() {
        when(failureDetectionService.getHealthyNodes())
            .thenReturn(Arrays.asList("node-2"));
        
        String recoveryId = recoveryManager.initiateRecovery("node-1");
        double progress = recoveryManager.getRecoveryProgress(recoveryId);
        
        assertEquals(0.0, progress);
    }
    
    @Test
    void testGetUnderReplicatedFileCountReturnsZero() {
        int count = recoveryManager.getUnderReplicatedFileCount();
        assertEquals(0, count);
    }
}
