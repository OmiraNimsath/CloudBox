package com.cloudbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents the status of a single node in the cluster.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeStatus {

    private int nodeId;
    private String host;
    private int port;
    private boolean alive;
    private boolean isLeader;
    private String role;         // "leader" | "follower"
    private long lastHeartbeat;  // epoch millis
}
