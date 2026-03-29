package com.cloudbox.controller;

import com.cloudbox.model.ApiResponse;
import com.cloudbox.model.ClusterStatus;
import com.cloudbox.model.ConsensusProposal;
import com.cloudbox.model.LeaderInfo;
import com.cloudbox.model.PartitionStatus;
import com.cloudbox.service.ClusterCoordinator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * ConsensusController exposes consensus & agreement endpoints.
 *
 * Provides REST API for:
 * - Cluster status monitoring
 * - Leader information
 * - Partition detection status
 * - Consensus proposals (atomic broadcasts)
 * - Cluster health checks
 *
 * Base path: /api/cluster/consensus
 */
@Slf4j
@RestController
@RequestMapping("/api/cluster/consensus")
public class ConsensusController {

    @Autowired
    private ClusterCoordinator clusterCoordinator;

    /**
     * GET /api/cluster/consensus/status
     *
     * Returns complete cluster status including:
     * - Leader information
     * - Partition status
     * - Active nodes
     * - Node statuses
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<ClusterStatus>> getClusterStatus() {
        try {
            ClusterStatus status = clusterCoordinator.getClusterStatus();
            return ResponseEntity.ok(ApiResponse.ok("Cluster status retrieved", status));
        } catch (Exception e) {
            log.error("Error retrieving cluster status", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to retrieve cluster status: " + e.getMessage()));
        }
    }

    /**
     * GET /api/cluster/consensus/leader
     *
     * Returns current leader information:
     * - Leader ID
     * - Election epoch
     * - ZXID
     * - Last heartbeat timestamp
     * - Alive status
     */
    @GetMapping("/leader")
    public ResponseEntity<ApiResponse<LeaderInfo>> getLeaderInfo() {
        try {
            LeaderInfo leader = clusterCoordinator.getLeaderInfo();

            if (leader == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("No leader elected yet"));
            }

            return ResponseEntity.ok(ApiResponse.ok("Leader information retrieved", leader));
        } catch (Exception e) {
            log.error("Error retrieving leader info", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to retrieve leader info: " + e.getMessage()));
        }
    }

    /**
     * GET /api/cluster/consensus/partition
     *
     * Returns partition status:
     * - Is partitioned (boolean)
     * - Reachable nodes count
     * - Can write (boolean)
     * - Response nodes
     * - Partition description
     */
    @GetMapping("/partition")
    public ResponseEntity<ApiResponse<PartitionStatus>> getPartitionStatus() {
        try {
            PartitionStatus partition = clusterCoordinator.getPartitionStatus();
            return ResponseEntity.ok(ApiResponse.ok("Partition status retrieved", partition));
        } catch (Exception e) {
            log.error("Error retrieving partition status", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to retrieve partition status: " + e.getMessage()));
        }
    }

    /**
     * POST /api/cluster/consensus/propose
     *
     * Propose a value for atomic broadcast (ZAB protocol).
     *
     * Request body: { "data": "operation/transaction data" }
     *
     * Returns the consensus proposal with:
     * - Proposal ID
     * - Epoch
     * - ZXID
     * - Data
     * - Proposer ID
     * - Timestamp
     *
     * @throws IllegalStateException if no quorum available (partitioned)
     */
    @PostMapping("/propose")
    public ResponseEntity<ApiResponse<ConsensusProposal>> propose(@RequestBody Map<String, String> request) {
        try {
            String data = request.get("data");
            if (data == null || data.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Missing or empty 'data' field in request"));
            }

            // Check if we have quorum
            if (!clusterCoordinator.canWrite()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(ApiResponse.error("Cannot propose: cluster partitioned, no quorum available"));
            }

            ConsensusProposal proposal = clusterCoordinator.propose(data);
            log.info("Proposal created: {} from node {}", proposal.getProposalId(), clusterCoordinator.getNodeId());

            return ResponseEntity.ok(ApiResponse.ok("Proposal created", proposal));
        } catch (IllegalStateException e) {
            log.warn("Cannot propose due to partition: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("Cannot propose: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Error proposing consensus", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to create proposal: " + e.getMessage()));
        }
    }

    /**
     * POST /api/cluster/consensus/ack
     * Acknowledge a proposal (follower confirms receipt).
     * Used by the leader during ZAB Phase 2 to collect quorum ACKs.
     */
    @PostMapping("/ack")
    public ResponseEntity<ApiResponse<String>> acknowledgeProposal(
            @RequestParam String proposalId) {
        log.debug("ACK received for proposal {} on node {}", proposalId, clusterCoordinator.getNodeId());
        return ResponseEntity.ok(ApiResponse.ok("ACK", proposalId));
    }

    /**
     * GET /api/cluster/consensus/heartbeat
     *
     * Health check endpoint for leader heartbeat mechanism.
     * Returns node ID and current timestamp.
     * Used by partition detection to verify node liveness.
     */
    @GetMapping("/heartbeat")
    public ResponseEntity<ApiResponse<Map<String, Object>>> heartbeat() {
        try {
            Map<String, Object> heartbeatData = new HashMap<>();
            heartbeatData.put("nodeId", clusterCoordinator.getNodeId());
            heartbeatData.put("timestamp", System.currentTimeMillis());
            heartbeatData.put("isLeader", clusterCoordinator.isLeader());
            heartbeatData.put("canWrite", clusterCoordinator.canWrite());

            return ResponseEntity.ok(ApiResponse.ok("Heartbeat acknowledged", heartbeatData));
        } catch (Exception e) {
            log.error("Error processing heartbeat", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Heartbeat processing failed: " + e.getMessage()));
        }
    }

    /**
     * GET /api/cluster/consensus/is-leader
     *
     * Quick check if this node is the current leader.
     */
    @GetMapping("/is-leader")
    public ResponseEntity<ApiResponse<Map<String, Object>>> isLeader() {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("nodeId", clusterCoordinator.getNodeId());
            data.put("isLeader", clusterCoordinator.isLeader());

            return ResponseEntity.ok(ApiResponse.ok("Leadership status", data));
        } catch (Exception e) {
            log.error("Error checking leadership", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Leadership check failed: " + e.getMessage()));
        }
    }

    /**
     * GET /api/cluster/consensus/can-write
     *
     * Check if this node can accept writes (has quorum, not partitioned).
     */
    @GetMapping("/can-write")
    public ResponseEntity<ApiResponse<Map<String, Object>>> canWrite() {
        try {
            boolean writable = clusterCoordinator.canWrite();
            PartitionStatus partition = clusterCoordinator.getPartitionStatus();

            Map<String, Object> data = new HashMap<>();
            data.put("canWrite", writable);
            data.put("reachableNodes", partition.getReachableNodes());
            data.put("isPartitioned", partition.isPartitioned());

            return ResponseEntity.ok(ApiResponse.ok("Write capability status", data));
        } catch (Exception e) {
            log.error("Error checking write capability", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Write capability check failed: " + e.getMessage()));
        }
    }

    /**
     * GET /api/cluster/consensus/health
     *
     * Extended health check with consensus module details.
     */
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> health() {
        try {
            ClusterStatus status = clusterCoordinator.getClusterStatus();

            Map<String, Object> health = new HashMap<>();
            health.put("nodeId", clusterCoordinator.getNodeId());
            health.put("initialized", clusterCoordinator.isInitialized());
            health.put("isLeader", clusterCoordinator.isLeader());
            health.put("canWrite", clusterCoordinator.canWrite());
            health.put("clusterHealthy", status.isClusterHealthy());
            health.put("activeNodeCount", status.getActiveNodes() != null ? status.getActiveNodes().size() : 0);
            health.put("partitioned", status.getPartitionStatus().isPartitioned());

            if (status.getLeader() != null) {
                health.put("currentLeaderId", status.getLeader().getLeaderId());
            }

            return ResponseEntity.ok(ApiResponse.ok("Consensus module health", health));
        } catch (Exception e) {
            log.error("Error checking consensus health", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Health check failed: " + e.getMessage()));
        }
    }
}
