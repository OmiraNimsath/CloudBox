package com.cloudbox.service;

import com.cloudbox.model.FileMetadata;
import java.util.List;

/**
 * Port for durable replica persistence.
 */
public interface StorageModulePort {

    void persistReplica(int nodeId, String fileId, byte[] content, long logicalTimestamp) throws Exception;

    void deleteReplica(int nodeId, String fileId) throws Exception;

    byte[] retrieveReplica(int nodeId, String fileId) throws Exception;

    List<FileMetadata> listFiles() throws Exception;
}
