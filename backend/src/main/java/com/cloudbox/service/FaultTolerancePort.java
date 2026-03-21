package com.cloudbox.service;

/**
 * Port for node health and failure tracking.
 */
public interface FaultTolerancePort {

    boolean isNodeWritable(int nodeId);

    void recordReplicationFailure(int nodeId, Exception exception);
}
