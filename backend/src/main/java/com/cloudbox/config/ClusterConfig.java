package com.cloudbox.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Central cluster constants and shared Spring beans.
 *
 * 5-node cluster: ports 8080–8084.
 * Quorum = ceil(N/2) + 1 = 3, ensuring writes survive up to 2 node failures.
 * Replication factor = 5 (every file is replicated to all nodes).
 */
@Configuration
public class ClusterConfig {

    /** Total nodes in the cluster. */
    public static final int NODE_COUNT = 5;

    /** Minimum live nodes required for quorum writes. */
    public static final int QUORUM_SIZE = 3;

    /** Minimum nodes that must confirm a file exists before a read is served. */
    public static final int READ_QUORUM = 3;

    /** Every file is replicated to all nodes. */
    public static final int REPLICATION_FACTOR = 5;

    /** First node port; node N uses port BASE_PORT + (N - 1). */
    public static final int BASE_PORT = 8080;

    /** Build the HTTP base URL for a given node id (1-based). */
    public static String nodeUrl(int nodeId) {
        return "http://localhost:" + (BASE_PORT + nodeId - 1);
    }

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        return new RestTemplate(factory);
    }
}
