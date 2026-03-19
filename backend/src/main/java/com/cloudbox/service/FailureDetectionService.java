package com.cloudbox.service;

import java.util.List;
import java.util.Map;

import com.cloudbox.model.NodeHealth;

/**
 * Detects node failures and manages node health status.
 * Uses ZooKeeper watches and heartbeat information to detect failures.
 */
public interface FailureDetectionService {
    
    /**
     * Initialize failure detection with ZooKeeper watches on all nodes.
     */
    void initialize();
    
    /**
     * Shutdown failure detection gracefully.
     */
    void shutdown();
    
    /**
     * Get health status for a specific node.
     */
    NodeHealth getNodeHealth(String nodeId);
    
    /**
     * Get health status for all nodes in the cluster.
     */
    Map<String, NodeHealth> getAllNodeHealth();
    
    /**
     * Get list of currently failed nodes.
     */
    List<String> getFailedNodes();
    
    /**
     * Get list of currently healthy nodes.
     */
    List<String> getHealthyNodes();
    
    /**
     * Check if a node is currently unhealthy.
     */
    boolean isNodeUnhealthy(String nodeId);
    
    /**
     * Check if the cluster has quorum (majority of nodes alive).
     */
    boolean hasQuorum();
    
    /**
     * Get count of healthy nodes.
     */
    int getHealthyNodeCount();
    
    /**
     * Manually mark a node as failed.
     */
    void markNodeAsFailed(String nodeId, String reason);
    
    /**
     * Clear failure status for a node when it recovers.
     */
    void clearFailureStatus(String nodeId);
}
