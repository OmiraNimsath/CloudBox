package com.cloudbox.model;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single heartbeat event from a node.
 * Used to track node liveness and detect failures.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HeartbeatInfo {
    private String nodeId;
    private LocalDateTime timestamp;
    private boolean isLeader;
    private int clusterSize;
    private int reachableNodes;
    private boolean partitioned;
    private long uptime; // milliseconds
}
