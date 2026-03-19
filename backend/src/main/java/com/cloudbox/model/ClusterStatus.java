package com.cloudbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;
import java.util.Set;

/**
 * Represents the overall state of the cluster for consensus monitoring.
 * 
 * - clusterHealthy: true if all nodes are reachable and functioning
 * - activeNodes: set of node IDs currently active in the cluster
 * - leader: information about current leader
 * - partitionStatus: details about any network partition
 * - timestamp: when this status snapshot was taken
 * - nodeStatuses: map of each node's individual status (can be extended)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterStatus {
    private boolean clusterHealthy;
    private Set<Integer> activeNodes;
    private LeaderInfo leader;
    private PartitionStatus partitionStatus;
    private long timestamp;
    private Map<Integer, String> nodeStatuses;
}
