package com.cloudbox.service;

import java.util.Set;

import com.cloudbox.domain.ConsistencyModel;

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
