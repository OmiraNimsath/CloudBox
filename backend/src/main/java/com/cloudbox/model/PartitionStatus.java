package com.cloudbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Set;

/**
 * Represents the partition status and cluster health during network failures.
 * 
 * - partitioned: true if a partition has been detected
 * - reachableNodes: count of nodes reachable from this node
 * - canWrite: true if reachableNodes >= quorum (3), allowing writes
 * - responseNodes: set of node IDs that responded to heartbeat
 * - detectionTime: timestamp when partition was detected
 * - partitionDescription: human-readable description of partition state
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartitionStatus {
    private boolean partitioned;
    private int reachableNodes;
    private boolean canWrite;
    private Set<Integer> responseNodes;
    private long detectionTime;
    private String partitionDescription;
}
