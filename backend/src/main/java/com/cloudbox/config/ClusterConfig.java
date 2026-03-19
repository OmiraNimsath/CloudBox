package com.cloudbox.config;

/**
 * Shared cluster constants used by all modules.
 *
 * These values match the team's technical specifications:
 * - 5 nodes, quorum = 3, RF = 5, 100 MB max file size.
 */
public final class ClusterConfig {

    private ClusterConfig() {}

    /** Total number of nodes in the cluster. */
    public static final int NODE_COUNT = 5;

    /** Minimum nodes required for a quorum (ceil(N/2) + 1 = 3). */
    public static final int QUORUM_SIZE = 3;

    /** Replication factor — every file is replicated to all nodes. */
    public static final int REPLICATION_FACTOR = 5;

    /** Maximum upload file size in bytes (100 MB). */
    public static final long MAX_FILE_SIZE = 100L * 1024 * 1024;

    /** Base port for the first node; nodes use ports 8080–8084. */
    public static final int BASE_PORT = 8080;

    /** ZooKeeper connection string. */
    public static final String ZK_CONNECTION = "localhost:2181";

    /** ZooKeeper namespace for CloudBox. */
    public static final String ZK_NAMESPACE = "/cloudbox";

    /** ZooKeeper path for leader election. */
    public static final String ZK_ELECTION_PATH = ZK_NAMESPACE + "/election";

    /** ZooKeeper path for cluster members. */
    public static final String ZK_MEMBERS_PATH = ZK_NAMESPACE + "/members";

    /** ZooKeeper path for distributed locks. */
    public static final String ZK_LOCKS_PATH = ZK_NAMESPACE + "/locks";

    /**
     * Get the HTTP base URL for a given node ID (1-based).
     */
    public static String getNodeUrl(int nodeId) {
        return "http://localhost:" + (BASE_PORT + nodeId - 1);
    }
}
