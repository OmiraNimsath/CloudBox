package com.cloudbox.service.replication;

/**
 * Port for durable replica persistence.
 */
public interface StorageModulePort {

    void persistReplica(int nodeId, String fileId, byte[] content, long logicalTimestamp) throws Exception;
}
