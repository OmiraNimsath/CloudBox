package com.cloudbox.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import com.cloudbox.config.ClusterConfig;
import com.cloudbox.model.ApiResponse;
import com.cloudbox.service.FailureDetectionService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private FailureDetectionService failureDetectionService;
    
    @Autowired
    private RestTemplate restTemplate;

    @PostMapping("/simulate-failure")
    public ResponseEntity<ApiResponse<String>> simulateFailure(@RequestParam String nodeId) {
        log.warn("Admin simulated failure for node: {}", nodeId);
        try {
            int nId = Integer.parseInt(nodeId.replace("node-", ""));
            String nodeUrl = ClusterConfig.getNodeUrl(nId) + "/api/internal/simulate-failure?fail=true";
            restTemplate.postForEntity(nodeUrl, null, String.class);
        } catch (Exception e) {
            log.error("Failed to forward simulate failure to node {}", nodeId, e);
        }
        // Do NOT instantly mark as failed — let heartbeat monitors on each node
        // accumulate 3 consecutive missed beats naturally before declaring the node
        // unhealthy. This matches realistic failure detection behaviour.
        return ResponseEntity.ok(ApiResponse.ok("Node " + nodeId + " failure simulation started", "Success"));
    }

    @PostMapping("/simulate-recovery")
    public ResponseEntity<ApiResponse<String>> simulateRecovery(@RequestParam String nodeId) {
        log.info("Admin simulated recovery for node: {}", nodeId);
        try {
            int nId = Integer.parseInt(nodeId.replace("node-", ""));
            String nodeUrl = ClusterConfig.getNodeUrl(nId) + "/api/internal/simulate-failure?fail=false";
            restTemplate.postForEntity(nodeUrl, null, String.class);
        } catch (Exception e) {
            log.error("Failed to forward simulate recovery to node {}", nodeId, e);
        }
        failureDetectionService.clearFailureStatus(nodeId.startsWith("node-") ? nodeId : "node-" + nodeId);
        return ResponseEntity.ok(ApiResponse.ok("Node " + nodeId + " marked as recovered", "Success"));
    }
}
