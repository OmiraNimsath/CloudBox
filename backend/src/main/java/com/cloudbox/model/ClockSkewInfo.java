package com.cloudbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Information about clock skew between nodes.
 * Used to detect and track time drift in the cluster.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClockSkewInfo {

    private int nodeId;
    private long skewMillis;      // Positive: node ahead, Negative: node behind
    private long maxSkewMillis;   // Maximum observed skew
    private boolean alertTriggered; // Alert if skew exceeds threshold
    private long lastMeasuredAt;  // Timestamp of last measurement
    private String nodeStatus;    // "HEALTHY", "FAILED", or "UNREACHABLE"
}
