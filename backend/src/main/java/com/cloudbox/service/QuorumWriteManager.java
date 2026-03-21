package com.cloudbox.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import com.cloudbox.config.ReplicationProperties;
import com.cloudbox.domain.ReplicaSelection;

@Service
@ConditionalOnBean({
    ConsensusModulePort.class,
    FaultTolerancePort.class,
    StorageModulePort.class,
    TimeSyncPort.class
})
public class QuorumWriteManager {

    private static final Logger log = LoggerFactory.getLogger(QuorumWriteManager.class);

    private final ReplicationStrategy replicationStrategy;
    private final ReplicationProperties replicationProperties;
    private final ConsensusModulePort consensusModulePort;
    private final FaultTolerancePort faultTolerancePort;
    private final StorageModulePort storageModulePort;
    private final TimeSyncPort timeSyncPort;

    public QuorumWriteManager(
            ReplicationStrategy replicationStrategy,
            ReplicationProperties replicationProperties,
            ConsensusModulePort consensusModulePort,
            FaultTolerancePort faultTolerancePort,
            StorageModulePort storageModulePort,
            TimeSyncPort timeSyncPort) {
        this.replicationStrategy = replicationStrategy;
        this.replicationProperties = replicationProperties;
        this.consensusModulePort = consensusModulePort;
        this.faultTolerancePort = faultTolerancePort;
        this.storageModulePort = storageModulePort;
        this.timeSyncPort = timeSyncPort;
    }

    public QuorumWriteResult replicateWrite(String fileId, byte[] content) {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId must not be blank");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("content must not be empty");
        }

        int leaderNodeId = consensusModulePort.getCurrentLeaderNodeId();
        ReplicaSelection selection = replicationStrategy.selectWriteReplicas(
                leaderNodeId,
                consensusModulePort.getClusterNodes());

        long logicalTimestamp = timeSyncPort.currentLogicalTimestamp();

        int maxAttempts = replicationProperties.getRetryCount() + 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Set<Integer> acknowledgedNodeIds = replicateAttempt(selection, fileId, content, logicalTimestamp);

            if (acknowledgedNodeIds.size() >= selection.requiredAcknowledgements()) {
                log.info(
                        "Quorum write successful: fileId={}, attempt={}, acknowledged={}/{} nodes={}, timestamp={}",
                        fileId,
                        attempt,
                        acknowledgedNodeIds.size(),
                        selection.requiredAcknowledgements(),
                        acknowledgedNodeIds,
                        logicalTimestamp);

                return new QuorumWriteResult(
                        fileId,
                        selection.consistencyModel(),
                        selection.requiredAcknowledgements(),
                        acknowledgedNodeIds,
                        logicalTimestamp,
                        attempt);
            }

            log.warn(
                    "Quorum write below threshold: fileId={}, attempt={}, acknowledged={}/{}",
                    fileId,
                    attempt,
                    acknowledgedNodeIds.size(),
                    selection.requiredAcknowledgements());
        }

        throw new QuorumWriteException(String.format(
                "Failed to reach quorum for fileId=%s after %d attempts",
                fileId,
                maxAttempts));
    }

    private Set<Integer> replicateAttempt(ReplicaSelection selection, String fileId, byte[] content, long logicalTimestamp) {
        List<CompletableFuture<Integer>> replicationFutures = new ArrayList<>();

        for (int nodeId : selection.targetNodeIds()) {
            if (!faultTolerancePort.isNodeWritable(nodeId)) {
                log.debug("Skipping non-writable node: nodeId={}", nodeId);
                continue;
            }

            CompletableFuture<Integer> nodeWriteFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    storageModulePort.persistReplica(nodeId, fileId, content, logicalTimestamp);
                    return nodeId;
                } catch (Exception exception) {
                    faultTolerancePort.recordReplicationFailure(nodeId, exception);
                    throw new RuntimeException(exception);
                }
            });
            replicationFutures.add(nodeWriteFuture);
        }

        if (replicationFutures.isEmpty()) {
            return Set.of();
        }

        CompletableFuture<Void> allWrites = CompletableFuture.allOf(replicationFutures.toArray(CompletableFuture[]::new));

        try {
            allWrites.get(replicationProperties.getWriteTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException timeoutOrFailure) {
            log.warn("Replication attempt ended before all writes completed: reason={}", timeoutOrFailure.getClass().getSimpleName());
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            log.warn("Replication attempt interrupted while awaiting acknowledgements");
        }

        Set<Integer> acknowledgedNodeIds = new LinkedHashSet<>();
        for (CompletableFuture<Integer> future : replicationFutures) {
            if (future.isDone() && !future.isCompletedExceptionally()) {
                Integer nodeId = future.getNow(null);
                if (nodeId != null) {
                    acknowledgedNodeIds.add(nodeId);
                }
            }
        }

        return acknowledgedNodeIds;
    }
}
