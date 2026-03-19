package com.cloudbox.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.cloudbox.model.RecoveryTask;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of RecoveryManager.
 * Manages recovery of failed nodes and re-synchronization of data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecoveryManagerImpl implements RecoveryManager {
    
    private final FailureDetectionService failureDetectionService;
    
    @Value("${cloudbox.max-recovery-retries:3}")
    private int maxRetries;
    
    @Value("${cloudbox.replication-factor:5}")
    private int expectedReplicationFactor;
    
    private final Map<String, RecoveryTask> recoveryTasks = new ConcurrentHashMap<>();
    private final Map<String, RecoveryTask> completedTasks = new ConcurrentHashMap<>();
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean recoveryEnabled = new AtomicBoolean(true);
    
    @Override
    public void initialize() {
        if (initialized.compareAndSet(false, true)) {
            log.info("Recovery manager initialized (max retries: {})", maxRetries);
        }
    }
    
    @Override
    public void shutdown() {
        if (initialized.compareAndSet(true, false)) {
            // Cancel all active recovery tasks
            recoveryTasks.values().forEach(task -> {
                if ("IN_PROGRESS".equals(task.getStatus())) {
                    task.setStatus("FAILED");
                    task.setErrorMessage("Recovery manager shutdown");
                }
            });
            recoveryTasks.clear();
            log.info("Recovery manager shutdown");
        }
    }
    
    @Override
    public String initiateRecovery(String failedNodeId) {
        if (!recoveryEnabled.get()) {
            log.warn("Recovery is disabled, cannot initiate recovery for node {}", failedNodeId);
            return null;
        }
        
        String recoveryId = "recovery-" + System.currentTimeMillis();
        
        // Find a healthy node to provide recovery data
        List<String> healthyNodes = failureDetectionService.getHealthyNodes();
        if (healthyNodes.isEmpty()) {
            log.error("No healthy nodes available for recovery of {}", failedNodeId);
            return null;
        }
        
        String sourceNodeId = healthyNodes.get(0);
        
        RecoveryTask task = RecoveryTask.builder()
            .recoveryId(recoveryId)
            .failedNodeId(failedNodeId)
            .createdAt(LocalDateTime.now())
            .startedAt(null)
            .completedAt(null)
            .status("PENDING")
            .sourceNodeId(sourceNodeId)
            .totalDataSize(0)
            .dataRecovered(0)
            .progressPercentage(0.0)
            .filesBeingRecovered(new ArrayList<>())
            .errorMessage(null)
            .retryCount(0)
            .maxRetries(maxRetries)
            .build();
        
        recoveryTasks.put(recoveryId, task);
        log.info("Recovery task {} initiated for node {} from source {}",
            recoveryId, failedNodeId, sourceNodeId);
        
        return recoveryId;
    }
    
    @Override
    public RecoveryTask getRecoveryTask(String recoveryId) {
        RecoveryTask task = recoveryTasks.get(recoveryId);
        return task != null ? task : completedTasks.get(recoveryId);
    }
    
    @Override
    public List<RecoveryTask> getActiveRecoveryTasks() {
        return new ArrayList<>(recoveryTasks.values());
    }
    
    @Override
    public List<RecoveryTask> getCompletedRecoveryTasks() {
        return new ArrayList<>(completedTasks.values());
    }
    
    @Override
    public void cancelRecovery(String recoveryId) {
        RecoveryTask task = recoveryTasks.get(recoveryId);
        if (task != null) {
            task.setStatus("FAILED");
            task.setErrorMessage("Recovery cancelled by user");
            task.setCompletedAt(LocalDateTime.now());
            recoveryTasks.remove(recoveryId);
            completedTasks.put(recoveryId, task);
            log.info("Recovery task {} cancelled", recoveryId);
        }
    }
    
    @Override
    public void retryRecovery(String recoveryId) {
        RecoveryTask task = recoveryTasks.get(recoveryId);
        if (task == null) {
            task = completedTasks.get(recoveryId);
        }
        
        if (task != null && task.getRetryCount() < task.getMaxRetries()) {
            task.setRetryCount(task.getRetryCount() + 1);
            task.setStatus("PENDING");
            task.setStartedAt(null);
            task.setCompletedAt(null);
            task.setDataRecovered(0);
            task.setProgressPercentage(0.0);
            recoveryTasks.put(recoveryId, task);
            completedTasks.remove(recoveryId);
            log.info("Recovery task {} retry #{}",
                recoveryId, task.getRetryCount());
        } else {
            log.warn("Cannot retry recovery task {} - max retries exceeded", recoveryId);
        }
    }
    
    @Override
    public double getRecoveryProgress(String recoveryId) {
        RecoveryTask task = getRecoveryTask(recoveryId);
        return task != null ? task.getProgressPercentage() : 0.0;
    }
    
    @Override
    public boolean isRecoveryEnabled() {
        return recoveryEnabled.get();
    }
    
    @Override
    public int getUnderReplicatedFileCount() {
        // Count files with replication factor less than expected
        // This is a simplified implementation - actual implementation would
        // query the storage/replication system
        return 0;
    }
    
    @Override
    public void triggerReReplication() {
        if (!recoveryEnabled.get()) {
            log.warn("Recovery is disabled, cannot trigger re-replication");
            return;
        }
        
        int underReplicatedCount = getUnderReplicatedFileCount();
        if (underReplicatedCount > 0) {
            log.info("Triggering re-replication for {} under-replicated files", 
                underReplicatedCount);
            // Actual re-replication logic would be implemented here
        }
    }
}
