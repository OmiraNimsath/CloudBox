package com.cloudbox.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cloudbox.model.ApiResponse;
import com.cloudbox.service.ConsensusService;
import com.cloudbox.service.NodeRegistry;
import com.cloudbox.service.ReplicationService;

/**
 * Admin endpoints: simulate node failure/recovery for demonstration,
 * plus internal node-to-node replication endpoint.
 */
@RestController
public class AdminController {

    private final NodeRegistry nodeRegistry;
    private final ReplicationService replicationService;
    private final ConsensusService consensusService;

    public AdminController(NodeRegistry nodeRegistry,
                           ReplicationService replicationService,
                           ConsensusService consensusService) {
        this.nodeRegistry = nodeRegistry;
        this.replicationService = replicationService;
        this.consensusService = consensusService;
    }

    // ── Admin simulation ──────────────────────────────────────────────────

    @PostMapping("/api/admin/simulate-failure")
    public ApiResponse<String> simulateFailure(@RequestParam int nodeId) {
        nodeRegistry.broadcastSimulatedFailure(nodeId);
        return ApiResponse.ok("Node " + nodeId + " failure simulated", "ok");
    }

    @PostMapping("/api/admin/simulate-recovery")
    public ApiResponse<String> simulateRecovery(@RequestParam int nodeId) {
        nodeRegistry.broadcastSimulatedRecovery(nodeId);
        return ApiResponse.ok("Node " + nodeId + " recovery simulated", "ok");
    }

    @PostMapping("/api/internal/mark-failed")
    public ResponseEntity<Void> markFailed(@RequestParam int nodeId) {
        nodeRegistry.simulateFailure(nodeId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/internal/mark-recovered")
    public ResponseEntity<Void> markRecovered(@RequestParam int nodeId) {
        nodeRegistry.simulateRecovery(nodeId);
        return ResponseEntity.ok().build();
    }

    // ── Internal replication (node-to-node) ───────────────────────────────

    /** Receives a file replica pushed by the primary after a quorum write. */
    @PostMapping("/api/internal/replicate")
    public ResponseEntity<Void> acceptReplica(
            @RequestParam String fileId,
            @RequestParam(defaultValue = "0") long timestamp,
            @RequestParam(defaultValue = "0") long uploadedAt,
            @RequestBody byte[] content) {
        replicationService.acceptReplica(fileId, content, timestamp, uploadedAt);
        return ResponseEntity.ok().build();
    }

    /** HEAD probe: returns 200 if this node has the file, 404 otherwise. */
    @RequestMapping(value = "/api/internal/replicate", method = RequestMethod.HEAD)
    public ResponseEntity<Void> hasReplica(@RequestParam String fileId) {
        return replicationService.existsLocally(fileId)
                ? ResponseEntity.ok().build()
                : ResponseEntity.notFound().build();
    }

    /** DELETE replica on this node (used during distributed delete). */
    @DeleteMapping("/api/internal/replicate")
    public ResponseEntity<Void> deleteReplica(@RequestParam String fileId) {
        replicationService.deleteLocal(fileId);
        return ResponseEntity.ok().build();
    }

    // ── Health ping (used by heartbeat monitor) ───────────────────────────

    @GetMapping("/api/health")
    public ResponseEntity<ApiResponse<String>> health() {
        if (nodeRegistry.isSelfFailed()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.ok("SIMULATED_FAILURE"));
        }
        return ResponseEntity.ok(ApiResponse.ok("UP"));
    }

    @PostMapping("/api/internal/consensus-sync")
    public ResponseEntity<Void> consensusSync(@RequestParam long epoch, @RequestParam long zxid) {
        consensusService.acceptEpochZxid(epoch, zxid);
        return ResponseEntity.ok().build();
    }

    // ── Metrics gossip ────────────────────────────────────────────────────

    /** Returns this node's current metrics snapshot (pulled by peers on startup). */
    @GetMapping("/api/internal/metrics-snapshot")
    public ResponseEntity<Map<String, Object>> metricsSnapshot() {
        return ResponseEntity.ok(nodeRegistry.exportMetrics());
    }

    /** Receives a metrics snapshot pushed by a peer during gossip. */
    @PostMapping("/api/internal/metrics-sync")
    public ResponseEntity<Void> metricsSync(@RequestBody Map<String, Object> snapshot) {
        nodeRegistry.importMetrics(snapshot);
        return ResponseEntity.ok().build();
    }
}
