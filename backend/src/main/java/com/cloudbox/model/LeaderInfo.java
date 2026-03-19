package com.cloudbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents information about the current leader in the cluster.
 * 
 * - leaderId: The node ID of the current leader (1-5)
 * - electionEpoch: Current election epoch (incremented on each re-election)
 * - zxid: ZooKeeper transaction ID for consensus ordering
 * - lastHeartbeat: Timestamp of last heartbeat from leader
 * - alive: Flag indicating if leader is still responsive
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaderInfo {
    private int leaderId;
    private long electionEpoch;
    private long zxid;
    private long lastHeartbeat;
    private boolean alive;
}
