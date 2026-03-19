package com.cloudbox.service;

import java.util.List;

import com.cloudbox.model.RecoveryTask;

/**
 * Manages recovery of failed nodes and re-synchronization of data.
 * Creates and tracks recovery tasks, restores data from replicas.
 */
public interface RecoveryManager {
    
    /**
     * Initialize recovery manager.
     */
    void initialize();
    
    /**
     * Shutdown recovery manager gracefully.
     */
    void shutdown();
    
    /**
     * Create a recovery task for a failed node.
     */
    String initiateRecovery(String failedNodeId);
    
    /**
     * Get a specific recovery task by ID.
     */
    RecoveryTask getRecoveryTask(String recoveryId);
    
    /**
     * Get all active recovery tasks.
     */
    List<RecoveryTask> getActiveRecoveryTasks();
    
    /**
     * Get all completed recovery tasks.
     */
    List<RecoveryTask> getCompletedRecoveryTasks();
    
    /**
     * Cancel a recovery task.
     */
    void cancelRecovery(String recoveryId);
    
    /**
     * Retry a failed recovery task.
     */
    void retryRecovery(String recoveryId);
    
    /**
     * Get the progress of a recovery task.
     */
    double getRecoveryProgress(String recoveryId);
    
    /**
     * Check if recovery is currently enabled.
     */
    boolean isRecoveryEnabled();
    
    /**
     * Get count of under-replicated files (files with RF < expected RF).
     */
    int getUnderReplicatedFileCount();
    
    /**
     * Trigger re-replication for under-replicated files.
     */
    void triggerReReplication();
}
