package com.cloudbox.service.replication;

import java.util.Set;

import com.cloudbox.domain.replication.ConsistencyModel;

/**
 * Outcome of a quorum write operation.
 */
public record QuorumWriteResult(
        String fileId,
        ConsistencyModel consistencyModel,
        int requiredAcknowledgements,
        Set<Integer> acknowledgedNodeIds,
        long logicalTimestamp,
        int attempts) {
}
