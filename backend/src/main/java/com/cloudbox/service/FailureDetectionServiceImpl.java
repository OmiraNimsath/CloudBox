package com.cloudbox.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.cloudbox.model.NodeHealth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of FailureDetectionService.
 * Detects node failures using heartbeat monitoring and ZooKeeper watches.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FailureDetectionServiceImpl implements FailureDetectionService {
    
    private final HeartbeatMonitor heartbeatMonitor;
    
    @Value("${cloudbox.cluster-size:5}")
    private int clusterSize;
    
    @Value("${cloudbox.quorum-size:3}")
    private int quorumSize;
    
    private final Map<String, NodeHealth> nodeHealthMap = new ConcurrentHashMap<>();
    private boolean initialized = false;
    
    @Override
    public void initialize() {
        if (!initialized) {
            heartbeatMonitor.startMonitoring();
            
            // Initialize health status for all known nodes
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
        int healthyCount = getHealthyNodes().size();
        return healthyCount >= quorumSize;
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
     * Update node health status based on heartbeat monitor data.
     */
    private void updateNodeHealth(String nodeId) {
        boolean alive = heartbeatMonitor.isNodeAlive(nodeId);
        long missedBeats = heartbeatMonitor.getMissedHeartbeats(nodeId);
        
        NodeHealth health = nodeHealthMap.get(nodeId);
        if (health != null) {
            health.setAlive(alive);
            health.setMissedHeartbeats(missedBeats);
            
            // Update status based on missed heartbeats
            if (!alive) {
                health.setStatus("UNHEALTHY");
                health.setFailureReason("Missed " + missedBeats + " heartbeats");
            } else if (missedBeats > 0 && missedBeats < 3) {
                health.setStatus("DEGRADED");
            } else {
                health.setStatus("HEALTHY");
            }
            
            // Update last heartbeat if available
            var lastBeat = heartbeatMonitor.getLastHeartbeat(nodeId);
            if (lastBeat != null && lastBeat.getTimestamp() != null) {
                health.setLastHeartbeat(lastBeat.getTimestamp());
            }
        }
    }
}
