package com.cloudbox.model;

import java.util.List;

/**
 * Snapshot of Berkeley Algorithm correction deltas across all cluster nodes.
 * Only the master node populates per-peer data; slaves show their own correction only.
 */
public record SkewReport(
        int nodeId,
        long thresholdMs,
        boolean synced,
        List<NodeSkew> skewDetails
) {
    public record NodeSkew(
            int    nodeId,
            long   nodeTimeMs,          // node's raw clock reading from the last Berkeley round (epoch ms)
            long   maxSkewMillis,       // peak |delta| observed across all rounds
            boolean alertTriggered,     // |skew| > threshold
            String nodeStatus,          // HEALTHY | FAILED | UNREACHABLE
            long   lastMeasuredAtMs,    // epoch ms when this node was last probed
            long   correctionDeltaMs    // delta pushed by master: avgTime − nodeTime
    ) {}
}
