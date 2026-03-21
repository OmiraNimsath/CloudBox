package com.cloudbox.domain;

/**
 * Replication consistency contract used by the write and read paths.
 */
public enum ConsistencyModel {
    QUORUM_WRITE_LEADER_READ
}