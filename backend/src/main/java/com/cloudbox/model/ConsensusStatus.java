package com.cloudbox.model;

import java.util.List;

/**
 * Consensus / leader-election state snapshot.
 *
 * Implements ZAB (ZooKeeper Atomic Broadcast) leader election via Curator LeaderSelector:
 * nodes compete for leadership using ZooKeeper ephemeral sequential nodes. The node with
 * the lowest sequential znode becomes leader. Epoch increments on each election.
 */
public record ConsensusStatus(
        int leaderId,
        long electionEpoch,
        long zxid,               // ZooKeeper transaction ID (monotonically increasing)
        long lastHeartbeatMs,    // epoch millis of leader's last heartbeat
        boolean leaderAlive,
        boolean partitioned,
        int reachableNodes,
        boolean canWrite,        // true when reachableNodes >= quorum
        List<Integer> activeNodes
) {}
