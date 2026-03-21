package com.cloudbox.service;

/**
 * Port for durable replica persistence.
 */
public interface StorageModulePort {

    void persistReplica(int nodeId, String fileId, byte[] content, long logicalTimestamp) throws Exception;
}
