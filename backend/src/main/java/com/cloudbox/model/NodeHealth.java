package com.cloudbox.model;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents the health status of a single node in the cluster.
 * Tracks heartbeat information and failure detection state.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeHealth {
    private String nodeId;
    private boolean alive;
    private LocalDateTime lastHeartbeat;
    private long missedHeartbeats;
    private String status; // HEALTHY, UNHEALTHY, RECOVERING, UNKNOWN
    private LocalDateTime statusTimestamp;
    private String failureReason; // null if healthy
    private int replicationFactor;
    private int healthyReplicaCount;
}
