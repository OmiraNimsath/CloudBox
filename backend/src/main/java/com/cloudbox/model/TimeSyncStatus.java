package com.cloudbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Overall status of time synchronization in the cluster.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimeSyncStatus {

    private int nodeId;
    private long localTime;       // This node's system time
    private long hlcPhysicalTime; // HLC physical time component
    private long hlcLogicalCounter; // HLC logical counter component
    private long logicalTimestamp;  // Lamport timestamp
    private boolean synced;       // Whether clocks are synchronized
    private long maxClockSkew;    // Maximum clock skew in cluster
    private int syncedNodeCount;  // Number of synced nodes
    private long lastSyncAt;      // Last successful sync time
    @Builder.Default
    private Map<Integer, ClockSkewInfo> nodeSkewMap = new HashMap<>(); // Skew per node
}
