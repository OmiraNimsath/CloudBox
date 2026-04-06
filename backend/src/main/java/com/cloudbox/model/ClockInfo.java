package com.cloudbox.model;

/**
 * Snapshot of time-synchronization state for this node.
 *
 * Berkeley Algorithm: master polls all slaves, computes cluster average,
 *   pushes individual correction deltas: δᵢ = avgTime − nodeTime_i.
 * HLC: physicalTime | logicalCounter to preserve causal ordering.
 * Lamport: monotonically increasing logical counter for event ordering.
 */
public record ClockInfo(
        int     nodeId,
        long    physicalTimeMs,         // System.currentTimeMillis()
        long    hlcPhysicalTime,        // HLC physical component
        int     hlcLogicalCounter,      // HLC tie-breaker counter
        long    lamportTimestamp,       // Lamport logical clock
        long    berkeleyCorrectionMs,   // accumulated correction applied by Berkeley algo
        long    lastRoundDeltaMs,       // correction delta from the most recent Berkeley round
        long    lastBerkeleyRoundMs,    // epoch ms of last round (master) / last correction received (slave)
        int     berkeleyMasterNodeId,   // node ID that ran the last round
        int     berkeleyRoundNumber,    // monotonic round counter (master-maintained)
        boolean isMaster,               // true if this node is the current Berkeley master
        boolean synced,                 // true if recently synced within threshold
        long    clockSkewThresholdMs    // configured acceptable skew
) {}
