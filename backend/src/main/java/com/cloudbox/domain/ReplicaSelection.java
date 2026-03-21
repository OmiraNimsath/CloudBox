package com.cloudbox.domain;

import java.util.List;

/**
 * Immutable result for a write replica selection decision.
 */
public record ReplicaSelection(
        ConsistencyModel consistencyModel,
        List<Integer> targetNodeIds,
        int requiredAcknowledgements) {
}