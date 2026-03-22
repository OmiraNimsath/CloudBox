package com.cloudbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Vector clock for tracking causality between events across distributed nodes.
 * Each node maintains a count of events it has processed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorClock {

    /**
     * Map of nodeId -> event count
     */
    @Builder.Default
    private Map<Integer, Long> clock = new HashMap<>();

    /**
     * Increment this node's counter.
     */
    public void increment(int nodeId) {
        clock.put(nodeId, clock.getOrDefault(nodeId, 0L) + 1);
    }

    /**
     * Update this vector clock with a remote clock: take element-wise maximum.
     * Then increment this node's counter.
     */
    public void update(VectorClock remote, int thisNodeId) {
        if (remote != null && remote.clock != null) {
            for (Map.Entry<Integer, Long> entry : remote.clock.entrySet()) {
                clock.put(entry.getKey(), Math.max(
                        clock.getOrDefault(entry.getKey(), 0L),
                        entry.getValue()
                ));
            }
        }
        increment(thisNodeId);
    }

    /**
     * Check if this vector clock happened-before another (all entries <=, at least one <).
     */
    public boolean happensBefore(VectorClock other) {
        if (other == null || other.clock == null) return false;

        boolean hasStrictlyLess = false;
        for (Map.Entry<Integer, Long> entry : clock.entrySet()) {
            long otherVal = other.clock.getOrDefault(entry.getKey(), 0L);
            if (entry.getValue() > otherVal) {
                return false; // This clock is ahead in some dimension
            }
            if (entry.getValue() < otherVal) {
                hasStrictlyLess = true;
            }
        }
        // Check dimensions only in other
        for (Integer nodeId : other.clock.keySet()) {
            if (!clock.containsKey(nodeId) && other.clock.get(nodeId) > 0) {
                hasStrictlyLess = true;
            }
        }

        return hasStrictlyLess;
    }

    /**
     * Check if two vector clocks are concurrent (neither happens-before the other).
     */
    public boolean isConcurrent(VectorClock other) {
        if (other == null) return false;
        return !this.happensBefore(other) && !other.happensBefore(this);
    }

    /**
     * Create a copy of this vector clock.
     */
    public VectorClock copy() {
        return VectorClock.builder()
                .clock(new HashMap<>(this.clock))
                .build();
    }
}
