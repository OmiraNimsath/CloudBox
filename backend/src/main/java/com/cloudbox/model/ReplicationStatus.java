package com.cloudbox.model;

import java.util.List;

/**
 * Summary of replication state: per-file replica counts and quorum info.
 */
public record ReplicationStatus(
        int replicationFactor,
        int quorumSize,
        String consistencyModel,         // e.g. QUORUM_CONSISTENCY
        int totalFiles,
        int fullyReplicatedFiles,
        int underReplicatedFiles,
        List<FileMetadata> files
) {}
