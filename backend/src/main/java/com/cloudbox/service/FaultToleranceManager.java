package com.cloudbox.service;

import com.cloudbox.model.FaultStatus;

/**
 * Orchestrates fault tolerance components.
 * Coordinates heartbeat monitoring, failure detection, and recovery.
 */
public interface FaultToleranceManager {
    
    /**
     * Initialize the fault tolerance system.
     */
    void initialize();
    
    /**
     * Shutdown the fault tolerance system gracefully.
     */
    void shutdown();
    
    /**
     * Get overall cluster fault status.
     */
    FaultStatus getFaultStatus();
    
    /**
     * Check if the system is fully initialized and operational.
     */
    boolean isInitialized();
    
    /**
     * Enable failure detection and recovery.
     */
    void enable();
    
    /**
     * Disable failure detection and recovery (maintenance mode).
     */
    void disable();
    
    /**
     * Check if fault tolerance is currently enabled.
     */
    boolean isEnabled();
}
