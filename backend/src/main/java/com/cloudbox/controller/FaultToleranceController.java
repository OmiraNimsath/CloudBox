package com.cloudbox.controller;

import com.cloudbox.model.*;
import com.cloudbox.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * REST API controller for Fault Tolerance operations.
 * Exposes endpoints for monitoring node health, managing recovery, and cluster status.
 */
@Slf4j
@RestController
@RequestMapping("/api/fault")
@RequiredArgsConstructor
public class FaultToleranceController {
    
    private final FaultToleranceManager faultToleranceManager;
    private final HeartbeatMonitor heartbeatMonitor;
    private final FailureDetectionService failureDetectionService;
    private final RecoveryManager recoveryManager;
    
    /**
     * GET /api/fault/status
     * Get overall cluster fault tolerance status.
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<FaultStatus>> getStatus() {
        FaultStatus status = faultToleranceManager.getFaultStatus();
        return ResponseEntity.ok(ApiResponse.<FaultStatus>builder()
            .success(true)
            .data(status)
            .build());
    }
    
    /**
     * GET /api/fault/node-health/{nodeId}
     * Get health status for a specific node.
     */
    @GetMapping("/node-health/{nodeId}")
    public ResponseEntity<ApiResponse<NodeHealth>> getNodeHealth(@PathVariable String nodeId) {
        NodeHealth health = failureDetectionService.getNodeHealth(nodeId);
        if (health == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ApiResponse.<NodeHealth>builder()
            .success(true)
            .data(health)
            .build());
    }
    
    /**
     * GET /api/fault/all-nodes
     * Get health status for all nodes in the cluster.
     */
    @GetMapping("/all-nodes")
    public ResponseEntity<ApiResponse<Map<String, NodeHealth>>> getAllNodes() {
        Map<String, NodeHealth> nodeHealthMap = failureDetectionService.getAllNodeHealth();
        return ResponseEntity.ok(ApiResponse.<Map<String, NodeHealth>>builder()
            .success(true)
            .data(nodeHealthMap)
            .build());
    }
    
    /**
     * GET /api/fault/failed-nodes
     * Get list of currently failed nodes.
     */
    @GetMapping("/failed-nodes")
    public ResponseEntity<ApiResponse<List<String>>> getFailedNodes() {
        List<String> failedNodes = failureDetectionService.getFailedNodes();
        return ResponseEntity.ok(ApiResponse.<List<String>>builder()
            .success(true)
            .data(failedNodes)
            .build());
    }
    
    /**
     * GET /api/fault/healthy-nodes
     * Get list of currently healthy nodes.
     */
    @GetMapping("/healthy-nodes")
    public ResponseEntity<ApiResponse<List<String>>> getHealthyNodes() {
        List<String> healthyNodes = failureDetectionService.getHealthyNodes();
        return ResponseEntity.ok(ApiResponse.<List<String>>builder()
            .success(true)
            .data(healthyNodes)
            .build());
    }
    
    /**
     * GET /api/fault/quorum-status
     * Check if cluster has quorum.
     */
    @GetMapping("/quorum-status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getQuorumStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("hasQuorum", failureDetectionService.hasQuorum());
        status.put("healthyNodeCount", failureDetectionService.getHealthyNodeCount());
        status.put("clusterState", faultToleranceManager. getFaultStatus().getClusterState());
        
        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
            .success(true)
            .data(status)
            .build());
    }
    
    /**
     * POST /api/fault/recovery/initiate
     * Initiate recovery for a failed node.
     * Request body: { "failedNodeId": "node-1" }
     */
    @PostMapping("/recovery/initiate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initiateRecovery(
            @RequestBody Map<String, String> request) {
        String failedNodeId = request.get("failedNodeId");
        if (failedNodeId == null || failedNodeId.isBlank()) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.<Map<String, Object>>builder()
                    .success(false)
                    .message("failedNodeId is required")
                    .build());
        }
        
        String recoveryId = recoveryManager.initiateRecovery(failedNodeId);
        if (recoveryId == null) {
            return ResponseEntity.status(503)
                .body(ApiResponse.<Map<String, Object>>builder()
                    .success(false)
                    .message("Cannot initiate recovery - no healthy nodes available")
                    .build());
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("recoveryId", recoveryId);
        response.put("failedNodeId", failedNodeId);
        
        log.info("Recovery initiated: {} for node {}", recoveryId, failedNodeId);
        return ResponseEntity.accepted()
            .body(ApiResponse.<Map<String, Object>>builder()
                .success(true)
                .data(response)
                .build());
    }
    
    /**
     * GET /api/fault/recovery/{recoveryId}
     * Get status of a specific recovery task.
     */
    @GetMapping("/recovery/{recoveryId}")
    public ResponseEntity<ApiResponse<RecoveryTask>> getRecoveryTask(@PathVariable String recoveryId) {
        RecoveryTask task = recoveryManager.getRecoveryTask(recoveryId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ApiResponse.<RecoveryTask>builder()
            .success(true)
            .data(task)
            .build());
    }
    
    /**
     * GET /api/fault/recovery/active
     * Get all active recovery tasks.
     */
    @GetMapping("/recovery/active")
    public ResponseEntity<ApiResponse<List<RecoveryTask>>> getActiveRecoveryTasks() {
        List<RecoveryTask> activeTasks = recoveryManager.getActiveRecoveryTasks();
        return ResponseEntity.ok(ApiResponse.<List<RecoveryTask>>builder()
            .success(true)
            .data(activeTasks)
            .build());
    }
    
    /**
     * GET /api/fault/recovery/completed
     * Get all completed recovery tasks.
     */
    @GetMapping("/recovery/completed")
    public ResponseEntity<ApiResponse<List<RecoveryTask>>> getCompletedRecoveryTasks() {
        List<RecoveryTask> completedTasks = recoveryManager.getCompletedRecoveryTasks();
        return ResponseEntity.ok(ApiResponse.<List<RecoveryTask>>builder()
            .success(true)
            .data(completedTasks)
            .build());
    }
    
    /**
     * POST /api/fault/recovery/{recoveryId}/cancel
     * Cancel an active recovery task.
     */
    @PostMapping("/recovery/{recoveryId}/cancel")
    public ResponseEntity<ApiResponse<Map<String, String>>> cancelRecovery(@PathVariable String recoveryId) {
        RecoveryTask task = recoveryManager.getRecoveryTask(recoveryId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        
        recoveryManager.cancelRecovery(recoveryId);
        Map<String, String> response = new HashMap<>();
        response.put("recoveryId", recoveryId);
        response.put("status", "FAILED");
        
        log.info("Recovery task {} cancelled", recoveryId);
        return ResponseEntity.ok(ApiResponse.<Map<String, String>>builder()
            .success(true)
            .data(response)
            .build());
    }
    
    /**
     * POST /api/fault/recovery/{recoveryId}/retry
     * Retry a failed recovery task.
     */
    @PostMapping("/recovery/{recoveryId}/retry")
    public ResponseEntity<ApiResponse<Map<String, String>>> retryRecovery(@PathVariable String recoveryId) {
        RecoveryTask task = recoveryManager.getRecoveryTask(recoveryId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        
        recoveryManager.retryRecovery(recoveryId);
        Map<String, String> response = new HashMap<>();
        response.put("recoveryId", recoveryId);
        response.put("status", "PENDING");
        
        log.info("Recovery task {} retried", recoveryId);
        return ResponseEntity.ok(ApiResponse.<Map<String, String>>builder()
            .success(true)
            .data(response)
            .build());
    }
    
    /**
     * GET /api/fault/heartbeats
     * Get all recorded heartbeats.
     */
    @GetMapping("/heartbeats")
    public ResponseEntity<ApiResponse<Map<String, HeartbeatInfo>>> getAllHeartbeats() {
        Map<String, HeartbeatInfo> heartbeats = heartbeatMonitor.getAllHeartbeats();
        return ResponseEntity.ok(ApiResponse.<Map<String, HeartbeatInfo>>builder()
            .success(true)
            .data(heartbeats)
            .build());
    }
    
    /**
     * POST /api/fault/enable
     * Enable fault tolerance (exit maintenance mode).
     */
    @PostMapping("/enable")
    public ResponseEntity<ApiResponse<Map<String, String>>> enable() {
        faultToleranceManager.enable();
        Map<String, String> response = new HashMap<>();
        response.put("status", "ENABLED");
        
        log.info("Fault tolerance system enabled");
        return ResponseEntity.ok(ApiResponse.<Map<String, String>>builder()
            .success(true)
            .data(response)
            .build());
    }
    
    /**
     * POST /api/fault/disable
     * Disable fault tolerance (maintenance mode).
     */
    @PostMapping("/disable")
    public ResponseEntity<ApiResponse<Map<String, String>>> disable() {
        faultToleranceManager.disable();
        Map<String, String> response = new HashMap<>();
        response.put("status", "DISABLED");
        
        log.warn("Fault tolerance system disabled (maintenance mode)");
        return ResponseEntity.ok(ApiResponse.<Map<String, String>>builder()
            .success(true)
            .data(response)
            .build());
    }
    
    /**
     * GET /api/fault/enabled
     * Check if fault tolerance is currently enabled.
     */
    @GetMapping("/enabled")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> isEnabled() {
        Map<String, Boolean> response = new HashMap<>();
        response.put("enabled", faultToleranceManager.isEnabled());
        
        return ResponseEntity.ok(ApiResponse.<Map<String, Boolean>>builder()
            .success(true)
            .data(response)
            .build());
    }
}
