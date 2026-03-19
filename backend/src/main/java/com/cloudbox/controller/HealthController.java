package com.cloudbox.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cloudbox.config.ClusterConfig;
import com.cloudbox.model.ApiResponse;

/**
 * Basic health / info endpoint.
 *
 * Returns this node's ID, port, and cluster config.
 * Available before any member-specific modules are wired.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${cloudbox.node-id:1}")
    private int nodeId;

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "nodeId", nodeId,
                "port", serverPort,
                "status", "UP",
                "nodeCount", ClusterConfig.NODE_COUNT,
                "quorumSize", ClusterConfig.QUORUM_SIZE,
                "replicationFactor", ClusterConfig.REPLICATION_FACTOR
        ));
    }
}
