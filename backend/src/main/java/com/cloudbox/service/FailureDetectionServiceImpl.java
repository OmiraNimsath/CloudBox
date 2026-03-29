package com.cloudbox.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.cloudbox.model.NodeHealth;

import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of FailureDetectionService.
 * Detects node failures using heartbeat monitoring (active HTTP pings).
 */
@Slf4j
@Service
public class FailureDetectionServiceImpl implements FailureDetectionService {

    private final HeartbeatMonitorImpl heartbeatMonitor;

    @Value("${cloudbox.cluster-size:5}")
    private int clusterSize;

    @Value("${cloudbox.quorum-size:3}")
    private int quorumSize;

    private final Map<String, NodeHealth> nodeHealthMap = new ConcurrentHashMap<>();
    private boolean initialized = false;

    public FailureDetectionServiceImpl(HeartbeatMonitorImpl heartbeatMonitor) {
        this.heartbeatMonitor = heartbeatMonitor;
    }

    @Override
    public void initialize() {
        if (!initialized) {
            heartbeatMonitor.startMonitoring();

            for (int i = 1; i <= clusterSize; i++) {
                String nodeId = "node-" + i;
                NodeHealth health = NodeHealth.builder()
                    .nodeId(nodeId)
                    .alive(true)
                    .lastHeartbeat(LocalDateTime.now())
                    .missedHeartbeats(0)
                    .status("HEALTHY")
                    .statusTimestamp(LocalDateTime.now())
                    .failureReason(null)
                    .replicationFactor(5)
                    .healthyReplicaCount(5)
                    .build();
                nodeHealthMap.put(nodeId, health);
            }

            initialized = true;
            log.info("Failure detection initialized for {} nodes (quorum: {})",
                clusterSize, quorumSize);
        }
    }

    @Override
    public void shutdown() {
        if (initialized) {
            heartbeatMonitor.stopMonitoring();
            nodeHealthMap.clear();
            initialized = false;
            log.info("Failure detection shutdown");
        }
    }

    @Override
    public NodeHealth getNodeHealth(String nodeId) {
        updateNodeHealth(nodeId);
        return nodeHealthMap.get(nodeId);
    }

    @Override
    public Map<String, NodeHealth> getAllNodeHealth() {
        nodeHealthMap.keySet().forEach(this::updateNodeHealth);
        return new ConcurrentHashMap<>(nodeHealthMap);
    }

    @Override
    public List<String> getFailedNodes() {
        return nodeHealthMap.entrySet().stream()
            .filter(e -> !e.getValue().isAlive())
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    @Override
    public List<String> getHealthyNodes() {
        return nodeHealthMap.entrySet().stream()
            .filter(e -> e.getValue().isAlive())
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    @Override
    public boolean isNodeUnhealthy(String nodeId) {
        updateNodeHealth(nodeId);
        NodeHealth health = nodeHealthMap.get(nodeId);
        return health != null && (!health.isAlive() ||
            "UNHEALTHY".equals(health.getStatus()) ||
            "RECOVERING".equals(health.getStatus()));
    }

    @Override
    public boolean hasQuorum() {
        return getHealthyNodes().size() >= quorumSize;
    }

    @Override
    public int getHealthyNodeCount() {
        return getHealthyNodes().size();
    }

    @Override
    public void markNodeAsFailed(String nodeId, String reason) {
        NodeHealth health = nodeHealthMap.get(nodeId);
        if (health != null) {
            health.setAlive(false);
            health.setStatus("UNHEALTHY");
            health.setFailureReason(reason);
            health.setStatusTimestamp(LocalDateTime.now());
            health.setMissedHeartbeats(3);
            heartbeatMonitor.forceMissedHeartbeats(nodeId, 3);
            log.warn("Node {} marked as failed: {}", nodeId, reason);
        }
    }

    @Override
    public void clearFailureStatus(String nodeId) {
        NodeHealth health = nodeHealthMap.get(nodeId);
        if (health != null) {
            health.setAlive(true);
            health.setStatus("HEALTHY");
            health.setFailureReason(null);
            health.setStatusTimestamp(LocalDateTime.now());
            health.setMissedHeartbeats(0);
            heartbeatMonitor.resetMissedHeartbeats(nodeId);
            log.info("Node {} recovered from failure", nodeId);
        }
    }

    /**
     * Refresh one node's health from HeartbeatMonitor (which actively HTTP-pings nodes).
     * Admin-simulated failures are never overwritten by the automatic check.
     */
    private void updateNodeHealth(String nodeId) {
        NodeHealth health = nodeHealthMap.get(nodeId);
        if (health == null) return;

        // Admin-simulated failure: keep it stuck UNLESS heartbeat monitor
        // confirms the node is actually alive again (i.e. recovery happened).
        if ("UNHEALTHY".equals(health.getStatus()) && health.getFailureReason() != null
                && !health.getFailureReason().startsWith("Missed ")
                && !health.getFailureReason().equals("Node unreachable")) {
            boolean alive = heartbeatMonitor.isNodeAlive(nodeId);
            if (!alive) {
                // Still truly down — keep admin status locked
                health.setMissedHeartbeats(heartbeatMonitor.getMissedHeartbeats(nodeId));
                health.setHealthyReplicaCount(0);
                return;
            }
            // Node is responding again → auto-clear the simulated failure
            log.info("Node {} is responding to heartbeats again — auto-clearing simulated failure", nodeId);
        }

        // Read real data from HeartbeatMonitor (it pings nodes every heartbeatInterval)
        boolean alive = heartbeatMonitor.isNodeAlive(nodeId);
        long missed = heartbeatMonitor.getMissedHeartbeats(nodeId);
        LocalDateTime lastSeen = heartbeatMonitor.getLastSeenTime(nodeId);
        int aliveCount = heartbeatMonitor.getAliveNodeCount();

        health.setAlive(alive);
        health.setMissedHeartbeats(missed);
        health.setHealthyReplicaCount(alive ? aliveCount : 0);

        if (alive) {
            health.setLastHeartbeat(lastSeen != null ? lastSeen : LocalDateTime.now());
            health.setStatus("HEALTHY");
            health.setFailureReason(null);
        } else {
            // Keep the last-seen time so the UI shows how long ago
            if (lastSeen != null) {
                health.setLastHeartbeat(lastSeen);
            }
            health.setStatus("UNHEALTHY");
            if (health.getFailureReason() == null) {
                health.setFailureReason("Node unreachable");
            }
        }

        health.setStatusTimestamp(LocalDateTime.now());
    }
}
