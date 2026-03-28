package com.cloudbox.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cloudbox.config.ClusterConfig;
import com.cloudbox.model.ApiResponse;
import com.cloudbox.service.ClusterCoordinator;
import com.cloudbox.service.FaultToleranceManager;
import lombok.extern.slf4j.Slf4j;

/**
 * Basic health / info endpoint.
 *
 * Returns this node's ID, port, and cluster config.
 * Available before any member-specific modules are wired.
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class HealthController {

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${cloudbox.node-id:1}")
    private int nodeId;

    @Autowired
    @Lazy
    private ClusterCoordinator clusterCoordinator;

    @Autowired
    @Lazy
    private FaultToleranceManager faultToleranceManager;

    private volatile boolean simulateFailure = false;

    public boolean isSimulatingFailure() {
        return simulateFailure;
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> health() {
        if (simulateFailure) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("Node is simulating failure"));
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "nodeId", nodeId,
                "port", serverPort,
                "status", "UP",
                "nodeCount", ClusterConfig.NODE_COUNT,
                "quorumSize", ClusterConfig.QUORUM_SIZE,
                "replicationFactor", ClusterConfig.REPLICATION_FACTOR
        )));
    }

    @PostMapping("/internal/simulate-failure")
    public void setSimulateFailure(@RequestParam boolean fail) {
        this.simulateFailure = fail;
        if (fail) {
            log.warn("Node {} is simulating failure. Stopping cluster coordination and fault tolerance.", nodeId);
            clusterCoordinator.stopClusterCoordination();
            faultToleranceManager.disable();
        } else {
            log.info("Node {} is recovering from simulated failure. Starting cluster coordination.", nodeId);
            clusterCoordinator.startClusterCoordination();
            faultToleranceManager.enable();
        }
    }
}
