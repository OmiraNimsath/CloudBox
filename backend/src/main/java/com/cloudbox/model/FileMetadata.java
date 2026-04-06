package com.cloudbox.model;

import java.util.List;

/**
 * Metadata for a file stored in the distributed cluster.
 */
public record FileMetadata(
        String name,
        String path,
        long sizeBytes,
        long uploadedAtMs,    // epoch millis
        long logicalTimestamp,
        List<Integer> presentOnNodes,
        int replicaCount,
        int expectedReplicas,
        boolean fullyReplicated
) {}
