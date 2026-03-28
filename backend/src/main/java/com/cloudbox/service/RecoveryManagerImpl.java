package com.cloudbox.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.cloudbox.model.FileMetadata;
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
    private final StorageModulePort storageModulePort;

    @Value("${cloudbox.max-recovery-retries:3}")
    private int maxRetries;
    
    @Value("${cloudbox.replication-factor:5}")
    private int expectedReplicationFactor;
    
    private final Map<String, RecoveryTask> recoveryTasks = new ConcurrentHashMap<>();
    private final Map<String, RecoveryTask> completedTasks = new ConcurrentHashMap<>();
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean recoveryEnabled = new AtomicBoolean(true);
    private ScheduledExecutorService recoveryExecutor;

    @Override
    public void initialize() {
        if (initialized.compareAndSet(false, true)) {
            recoveryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "RecoveryManager-Scheduler");
                t.setDaemon(true);
                return t;
            });
            recoveryExecutor.scheduleWithFixedDelay(this::executeRecoveryTasks, 5, 5, TimeUnit.SECONDS);
            log.info("Recovery manager initialized (max retries: {}, executor started)", maxRetries);
        }
    }
    
    @Override
    public void shutdown() {
        if (initialized.compareAndSet(true, false)) {
            if (recoveryExecutor != null) {
                recoveryExecutor.shutdown();
            }
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
        try {
            List<FileMetadata> files = storageModulePort.listFiles();
            int underReplicated = 0;
            for (FileMetadata meta : files) {
                int replicaCount = 0;
                for (int nodeId = 1; nodeId <= expectedReplicationFactor; nodeId++) {
                    try {
                        byte[] data = storageModulePort.retrieveReplica(nodeId, meta.getName());
                        if (data != null && data.length > 0) {
                            replicaCount++;
                        }
                    } catch (Exception ignored) {
                        // node unreachable or replica absent — counts as missing
                    }
                }
                if (replicaCount < expectedReplicationFactor) {
                    underReplicated++;
                }
            }
            return underReplicated;
        } catch (Exception e) {
            log.warn("Could not determine under-replicated file count: {}", e.getMessage());
            return 0;
        }
    }
    
    @Override
    public void triggerReReplication() {
        if (!recoveryEnabled.get()) {
            log.warn("Recovery is disabled, cannot trigger re-replication");
            return;
        }
        int underReplicatedCount = getUnderReplicatedFileCount();
        if (underReplicatedCount > 0) {
            log.info("Triggering re-replication for {} under-replicated files", underReplicatedCount);
            List<String> healthyNodes = failureDetectionService.getHealthyNodes();
            if (!healthyNodes.isEmpty()) {
                String syntheticNodeId = "under-replicated-" + System.currentTimeMillis();
                initiateRecovery(syntheticNodeId);
            }
        }
    }

    /**
     * Scheduled check: pick up PENDING recovery tasks and execute them asynchronously.
     * Runs every 5 seconds via the recoveryExecutor.
     */
    private void executeRecoveryTasks() {
        recoveryTasks.values().stream()
            .filter(t -> "PENDING".equals(t.getStatus()))
            .forEach(task -> {
                task.setStatus("IN_PROGRESS");
                Thread.ofVirtual()
                    .name("recovery-" + task.getRecoveryId())
                    .start(() -> runRecovery(task));
            });
    }

    /**
     * Execute a single recovery task: copy all files from the source node to the failed node.
     * Uses the Gossip / self-healing pattern — restores full RF=5 replication.
     */
    private void runRecovery(RecoveryTask task) {
        try {
            task.setStartedAt(LocalDateTime.now());
            int failedNode  = Integer.parseInt(task.getFailedNodeId().replace("node-", ""));
            int sourceNode  = Integer.parseInt(task.getSourceNodeId().replace("node-", ""));

            List<FileMetadata> files = storageModulePort.listFiles();
            List<String> fileNames  = files.stream().map(FileMetadata::getName).collect(Collectors.toList());
            long totalSize          = files.stream().mapToLong(FileMetadata::getSize).sum();

            task.setFilesBeingRecovered(fileNames);
            task.setTotalDataSize(totalSize == 0 ? 1 : totalSize);

            long recovered = 0;
            for (FileMetadata meta : files) {
                try {
                    byte[] content = storageModulePort.retrieveReplica(sourceNode, meta.getName());
                    storageModulePort.persistReplica(failedNode, meta.getName(), content, System.currentTimeMillis());
                    recovered += meta.getSize();
                    task.setDataRecovered(recovered);
                    task.setProgressPercentage(Math.min(99.0, (recovered * 100.0) / task.getTotalDataSize()));
                } catch (Exception e) {
                    log.warn("Skipping file {} during recovery of node {}: {}",
                        meta.getName(), task.getFailedNodeId(), e.getMessage());
                }
            }

            task.setProgressPercentage(100.0);
            task.setStatus("COMPLETED");
            task.setCompletedAt(LocalDateTime.now());
            recoveryTasks.remove(task.getRecoveryId());
            completedTasks.put(task.getRecoveryId(), task);
            log.info("Recovery task {} completed for node {}: {} files processed",
                task.getRecoveryId(), task.getFailedNodeId(), fileNames.size());

        } catch (Exception e) {
            task.setStatus("FAILED");
            task.setErrorMessage(e.getMessage());
            task.setCompletedAt(LocalDateTime.now());
            recoveryTasks.remove(task.getRecoveryId());
            completedTasks.put(task.getRecoveryId(), task);
            log.error("Recovery task {} failed for node {}: {}",
                task.getRecoveryId(), task.getFailedNodeId(), e.getMessage());
        }
    }
}
