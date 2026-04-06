package com.cloudbox.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cloudbox.model.ApiResponse;
import com.cloudbox.model.FaultStatus;
import com.cloudbox.model.NodeStatus;
import com.cloudbox.service.NodeRegistry;
import com.cloudbox.service.ReplicationService;

/**
 * Fault tolerance status: node health, quorum state, MTTF/MTTR/availability,
 * and admin simulation endpoints.
 */
@RestController
@RequestMapping("/api/fault")
public class FaultController {

    private final NodeRegistry nodeRegistry;
    private final ReplicationService replicationService;

    public FaultController(NodeRegistry nodeRegistry,
                           ReplicationService replicationService) {
        this.nodeRegistry = nodeRegistry;
        this.replicationService = replicationService;
    }

    @GetMapping("/status")
    public ApiResponse<FaultStatus> getStatus() {
        Map<Integer, NodeStatus> statuses = nodeRegistry.getAllStatuses();
        int healthy = (int) statuses.values().stream().filter(NodeStatus::alive).count();
        int failed  = statuses.size() - healthy;
        String clusterState = failed == 0 ? "HEALTHY"
                            : healthy >= 3 ? "DEGRADED" : "CRITICAL";

        List<String> recentFailures = new ArrayList<>();
        statuses.values().stream()
                .filter(s -> !s.alive() && s.failureReason() != null)
                .forEach(s -> recentFailures.add("Node " + s.nodeId() + ": " + s.failureReason()));

        FaultStatus status = new FaultStatus(
                clusterState,
                nodeRegistry.totalNodes(),
                healthy,
                failed,
                nodeRegistry.hasQuorum(),
                nodeRegistry.getMttfSeconds(),
                nodeRegistry.getMttrSeconds(),
                nodeRegistry.getAvailabilityPercent(),
                replicationService.underReplicatedCount(),
                statuses,
                recentFailures);

        return ApiResponse.ok(status);
    }
}