package com.cloudbox.service.replication;

/**
 * Port for cluster-coordinated logical time.
 */
public interface TimeSyncPort {

    long currentLogicalTimestamp();
}
