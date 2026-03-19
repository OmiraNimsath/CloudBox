package com.cloudbox.service;

import java.util.Map;

import com.cloudbox.model.HeartbeatInfo;

/**
 * Monitors heartbeats from all nodes in the cluster.
 * Detects node failures based on missed heartbeats.
 */
public interface HeartbeatMonitor {
    
    /**
     * Start monitoring heartbeats from all cluster nodes.
     */
    void startMonitoring();
    
    /**
     * Stop monitoring heartbeats.
     */
    void stopMonitoring();
    
    /**
     * Record a heartbeat from a node.
     */
    void recordHeartbeat(HeartbeatInfo heartbeat);
    
    /**
     * Get the last heartbeat info for a specific node.
     */
    HeartbeatInfo getLastHeartbeat(String nodeId);
    
    /**
     * Get heartbeat info for all nodes.
     */
    Map<String, HeartbeatInfo> getAllHeartbeats();
    
    /**
     * Check if a node is currently considered alive.
     */
    boolean isNodeAlive(String nodeId);
    
    /**
     * Get the number of missed heartbeats for a node.
     */
    long getMissedHeartbeats(String nodeId);
    
    /**
     * Reset missed heartbeat counter for a node.
     */
    void resetMissedHeartbeats(String nodeId);
    
    /**
     * Check if monitoring is currently active.
     */
    boolean isMonitoring();
}
