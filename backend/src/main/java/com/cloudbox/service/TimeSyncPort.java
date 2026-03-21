package com.cloudbox.service;

/**
 * Port for cluster-coordinated logical time.
 */
public interface TimeSyncPort {

    long currentLogicalTimestamp();
}
