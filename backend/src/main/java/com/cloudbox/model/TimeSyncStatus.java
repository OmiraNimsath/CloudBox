package com.cloudbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    private boolean synced;              // Whether clocks are synchronized
    private long maxClockSkew;           // Historical peak skew (never decreases until reset)
    private long currentMaxClockSkew;    // Live max from current skew readings
    private int syncedNodeCount;         // Number of synced nodes (including self)
    private int totalNodes;       // Total nodes in cluster
    private long lastSyncAt;      // Last skew detection cycle
    private long ntpOffsetMs;     // Cristian's algorithm offset in milliseconds
    private long lastNtpSyncAt;   // Timestamp of last successful NTP/Cristian sync
    private Map<Integer, ClockSkewInfo> nodeSkewMap;
}
