package com.cloudbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Hybrid Logical Clock (HLC) combining physical time and logical ordering.
 * Provides both wall-clock accuracy and causal ordering guarantees.
 *
 * Structure: (pt, l, nodeId) where:
 *   - pt: physical time (milliseconds since epoch)
 *   - l: logical counter (for ordering events with same physical time)
 *   - nodeId: node identifier for deterministic ordering
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HybridLogicalClock implements Comparable<HybridLogicalClock> {

    private long physicalTime;   // wall-clock time (ms since epoch)
    private long logicalCounter; // logical sequence number
    private int nodeId;           // node identifier

    /**
     * Initialize HLC with current system time.
     */
    public static HybridLogicalClock now(int nodeId) {
        return HybridLogicalClock.builder()
                .physicalTime(System.currentTimeMillis())
                .logicalCounter(0)
                .nodeId(nodeId)
                .build();
    }

    /**
     * Increment logical counter (used when multiple events occur at same physical time).
     */
    public void increment() {
        this.logicalCounter++;
    }

    /**
     * Update HLC upon sending/processing an event.
     * Current time >= last physical time ensures monotonicity.
     */
    public void updateSend(int thisNodeId) {
        long now = System.currentTimeMillis();
        if (now > this.physicalTime) {
            this.physicalTime = now;
            this.logicalCounter = 0;
        } else {
            this.logicalCounter++;
        }
        this.nodeId = thisNodeId;
    }

    /**
     * Update HLC upon receiving a remote HLC.
     * Merges remote clock with local time, ensuring causality.
     */
    public void updateReceive(HybridLogicalClock remote, int thisNodeId) {
        long now = System.currentTimeMillis();

        if (now > Math.max(this.physicalTime, remote.physicalTime)) {
            // New physical time is ahead of both
            this.physicalTime = now;
            this.logicalCounter = 0;
        } else if (Math.max(this.physicalTime, remote.physicalTime) > now) {
            // One or both clocks are ahead of wall time (clock skew detected).
            // Compare BEFORE overwriting physicalTime to avoid the dead-branch bug.
            if (remote.physicalTime > this.physicalTime) {
                // Remote is strictly ahead: adopt its physical time and counter
                this.physicalTime = remote.physicalTime;
                this.logicalCounter = remote.logicalCounter + 1;
            } else if (this.physicalTime > remote.physicalTime) {
                // Local is strictly ahead: keep physical time, just increment counter
                this.logicalCounter++;
            } else {
                // Both equal: keep physical time, take max of both counters
                this.logicalCounter = Math.max(this.logicalCounter, remote.logicalCounter) + 1;
            }
        } else {
            // Same physical time
            this.logicalCounter = Math.max(this.logicalCounter, remote.logicalCounter) + 1;
        }

        this.nodeId = thisNodeId;
    }

    /**
     * Create a copy of this HLC.
     */
    public HybridLogicalClock copy() {
        return HybridLogicalClock.builder()
                .physicalTime(this.physicalTime)
                .logicalCounter(this.logicalCounter)
                .nodeId(this.nodeId)
                .build();
    }

    /**
     * Compare two HLC values for ordering.
     * Order by: physical time, then logical counter, then node ID.
     */
    @Override
    public int compareTo(HybridLogicalClock other) {
        if (other == null) return 1;

        if (this.physicalTime != other.physicalTime) {
            return Long.compare(this.physicalTime, other.physicalTime);
        }
        if (this.logicalCounter != other.logicalCounter) {
            return Long.compare(this.logicalCounter, other.logicalCounter);
        }
        return Integer.compare(this.nodeId, other.nodeId);
    }
}
