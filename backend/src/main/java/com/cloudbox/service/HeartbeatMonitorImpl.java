package com.cloudbox.service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.cloudbox.config.ClusterConfig;
import com.cloudbox.model.HeartbeatInfo;

import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of HeartbeatMonitor.
 * Actively pings every node in the cluster on a fixed interval and tracks
 * heartbeat data (last-seen time, missed-beat counter) for failure detection.
 */
@Slf4j
@Service
public class HeartbeatMonitorImpl implements HeartbeatMonitor {

    @Autowired(required = false)
    private RestTemplate restTemplate;

    @Value("${cloudbox.heartbeat-interval:5000}")
    private long heartbeatInterval;

    @Value("${cloudbox.node-id:1}")
    private int selfNodeId;

    /** Last successful heartbeat per node. */
    private final Map<String, HeartbeatInfo> heartbeatMap = new ConcurrentHashMap<>();
    /** Consecutive missed heartbeats per node. */
    private final Map<String, Long> missedHeartbeatMap = new ConcurrentHashMap<>();
    /** Timestamp of last successful heartbeat per node (for "time ago" display). */
    private final Map<String, LocalDateTime> lastSeenMap = new ConcurrentHashMap<>();

    private final AtomicBoolean monitoring = new AtomicBoolean(false);
    private ScheduledExecutorService executorService;

    // ─── lifecycle ────────────────────────────────────────────

    @Override
    public void startMonitoring() {
        if (monitoring.compareAndSet(false, true)) {
            // Seed every node as alive initially
            for (int i = 1; i <= ClusterConfig.NODE_COUNT; i++) {
                String nid = "node-" + i;
                missedHeartbeatMap.put(nid, 0L);
                lastSeenMap.put(nid, LocalDateTime.now());
            }

            executorService = Executors.newScheduledThreadPool(ClusterConfig.NODE_COUNT, r -> {
                Thread t = new Thread(r, "HeartbeatMonitor-Worker");
                t.setDaemon(true);
                return t;
            });

            // Stagger each node's first ping evenly across the interval so "last heartbeat"
            // timestamps are spread out rather than all arriving at the same instant.
            long stagger = heartbeatInterval / ClusterConfig.NODE_COUNT;
            for (int i = 1; i <= ClusterConfig.NODE_COUNT; i++) {
                final int targetNodeId = i;
                executorService.scheduleAtFixedRate(
                    () -> pingAndRecord(targetNodeId),
                    stagger * (targetNodeId - 1),   // node-1: 0ms, node-2: 1000ms, …
                    heartbeatInterval,
                    TimeUnit.MILLISECONDS
                );
            }

            log.info("Heartbeat monitoring started (interval: {}ms, stagger: {}ms, selfNode: {})",
                heartbeatInterval, stagger, selfNodeId);
        }
    }

    @Override
    public void stopMonitoring() {
        if (monitoring.compareAndSet(true, false)) {
            if (executorService != null) {
                executorService.shutdown();
                try {
                    if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                        executorService.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executorService.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            log.info("Heartbeat monitoring stopped");
        }
    }

    // ─── active heartbeat check ───────────────────────────────

    /**
     * Ping a single node and record the result.
     * Called on a per-node staggered schedule so each node's last-seen
     * timestamp is updated independently.
     */
    private void pingAndRecord(int remoteNodeId) {
        String nodeKey = "node-" + remoteNodeId;
        boolean reachable = pingNode(remoteNodeId);

        if (reachable) {
            HeartbeatInfo hb = HeartbeatInfo.builder()
                .nodeId(nodeKey)
                .timestamp(LocalDateTime.now())
                .isLeader(false)
                .clusterSize(ClusterConfig.NODE_COUNT)
                .build();
            heartbeatMap.put(nodeKey, hb);
            missedHeartbeatMap.put(nodeKey, 0L);
            lastSeenMap.put(nodeKey, LocalDateTime.now());
        } else {
            long prev = missedHeartbeatMap.getOrDefault(nodeKey, 0L);
            missedHeartbeatMap.put(nodeKey, prev + 1);

            if (prev + 1 == 3) {
                log.warn("Node {} exceeded heartbeat threshold — 3 consecutive misses", nodeKey);
            }
        }
    }

    private boolean pingNode(int remoteNodeId) {
        // This node is always reachable to itself
        if (remoteNodeId == selfNodeId) return true;
        try {
            if (restTemplate == null) return true;
            String url = ClusterConfig.getNodeUrl(remoteNodeId) + "/api/health";
            restTemplate.getForObject(url, String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ─── query methods ────────────────────────────────────────

    @Override
    public void recordHeartbeat(HeartbeatInfo heartbeat) {
        if (heartbeat != null && heartbeat.getNodeId() != null) {
            heartbeatMap.put(heartbeat.getNodeId(), heartbeat);
            missedHeartbeatMap.put(heartbeat.getNodeId(), 0L);
            lastSeenMap.put(heartbeat.getNodeId(), LocalDateTime.now());
        }
    }

    @Override
    public HeartbeatInfo getLastHeartbeat(String nodeId) {
        return heartbeatMap.get(nodeId);
    }

    @Override
    public Map<String, HeartbeatInfo> getAllHeartbeats() {
        return new ConcurrentHashMap<>(heartbeatMap);
    }

    @Override
    public boolean isNodeAlive(String nodeId) {
        return missedHeartbeatMap.getOrDefault(nodeId, 0L) < 3;
    }

    @Override
    public long getMissedHeartbeats(String nodeId) {
        return missedHeartbeatMap.getOrDefault(nodeId, 0L);
    }

    @Override
    public void resetMissedHeartbeats(String nodeId) {
        missedHeartbeatMap.put(nodeId, 0L);
        lastSeenMap.put(nodeId, LocalDateTime.now());
    }

    @Override
    public void forceMissedHeartbeats(String nodeId, long count) {
        missedHeartbeatMap.put(nodeId, count);
    }

    @Override
    public boolean isMonitoring() {
        return monitoring.get();
    }

    /**
     * Get the timestamp of the last successful heartbeat for a node.
     */
    public LocalDateTime getLastSeenTime(String nodeId) {
        return lastSeenMap.get(nodeId);
    }

    /**
     * Count how many nodes are currently considered alive (missed < 3).
     */
    public int getAliveNodeCount() {
        return (int) missedHeartbeatMap.values().stream().filter(m -> m < 3).count();
    }
}
