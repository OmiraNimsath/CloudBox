package com.cloudbox.model;

/**
 * Represents the live health state of a single cluster node.
 */
public record NodeStatus(
        int nodeId,
        String status,        // HEALTHY | UNHEALTHY | RECOVERING
        boolean alive,
        boolean leader,
        long lastHeartbeatMs, // epoch millis of last successful heartbeat
        int missedHeartbeats,
        String failureReason  // null when healthy
) {}
