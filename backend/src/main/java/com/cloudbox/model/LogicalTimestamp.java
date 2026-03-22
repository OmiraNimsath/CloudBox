package com.cloudbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lamport logical clock implementation for event ordering.
 * Maintains a monotonically increasing counter that tracks causal relationships.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogicalTimestamp implements Comparable<LogicalTimestamp> {

    private long timestamp;
    private int nodeId;

    /**
     * Increment the logical clock for the local node.
     */
    public void increment() {
        this.timestamp++;
    }

    /**
     * Update the logical clock upon receiving a message with a remote timestamp.
     * Ensures: local_ts = max(local_ts, remote_ts) + 1
     */
    public void update(LogicalTimestamp remote) {
        if (remote != null) {
            this.timestamp = Math.max(this.timestamp, remote.timestamp) + 1;
        } else {
            this.increment();
        }
    }

    /**
     * Create a new logical timestamp with incremented value.
     */
    public LogicalTimestamp next() {
        return LogicalTimestamp.builder()
                .timestamp(this.timestamp + 1)
                .nodeId(this.nodeId)
                .build();
    }

    @Override
    public int compareTo(LogicalTimestamp other) {
        if (other == null) return 1;
        if (this.timestamp != other.timestamp) {
            return Long.compare(this.timestamp, other.timestamp);
        }
        return Integer.compare(this.nodeId, other.nodeId);
    }
}
