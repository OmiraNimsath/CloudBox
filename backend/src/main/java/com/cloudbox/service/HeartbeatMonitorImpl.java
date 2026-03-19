package com.cloudbox.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.cloudbox.model.HeartbeatInfo;

import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of HeartbeatMonitor.
 * Records heartbeats from all nodes and tracks missed heartbeats for failure detection.
 */
@Slf4j
@Service
public class HeartbeatMonitorImpl implements HeartbeatMonitor {
    
    @Value("${cloudbox.heartbeat-interval:5000}")
    private long heartbeatInterval;
    
    @Value("${cloudbox.heartbeat-timeout:15000}")
    private long heartbeatTimeout;
    
    private final Map<String, HeartbeatInfo> heartbeatMap = new ConcurrentHashMap<>();
    private final Map<String, Long> missedHeartbeatMap = new ConcurrentHashMap<>();
    private final AtomicBoolean monitoring = new AtomicBoolean(false);
    private ScheduledExecutorService executorService;
    
    @Override
    public void startMonitoring() {
        if (monitoring.compareAndSet(false, true)) {
            executorService = Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "HeartbeatMonitor-Worker");
                t.setDaemon(true);
                return t;
            });
            
            // Periodic check for stale heartbeats
            executorService.scheduleAtFixedRate(
                this::checkForStaleHeartbeats,
                heartbeatInterval,
                heartbeatInterval,
                TimeUnit.MILLISECONDS
            );
            
            log.info("Heartbeat monitoring started (interval: {}ms, timeout: {}ms)", 
                heartbeatInterval, heartbeatTimeout);
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
    
    @Override
    public void recordHeartbeat(HeartbeatInfo heartbeat) {
        if (heartbeat != null && heartbeat.getNodeId() != null) {
            heartbeatMap.put(heartbeat.getNodeId(), heartbeat);
            missedHeartbeatMap.put(heartbeat.getNodeId(), 0L);
            log.debug("Recorded heartbeat from node: {}", heartbeat.getNodeId());
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
        Long missed = missedHeartbeatMap.getOrDefault(nodeId, 0L);
        return missed < 3; // Threshold: 3 missed heartbeats = failure
    }
    
    @Override
    public long getMissedHeartbeats(String nodeId) {
        return missedHeartbeatMap.getOrDefault(nodeId, 0L);
    }
    
    @Override
    public void resetMissedHeartbeats(String nodeId) {
        missedHeartbeatMap.put(nodeId, 0L);
    }
    
    @Override
    public boolean isMonitoring() {
        return monitoring.get();
    }
    
    /**
     * Periodically check for stale heartbeats.
     * Increment missed heartbeat counter for nodes without recent heartbeats.
     */
    private void checkForStaleHeartbeats() {
        LocalDateTime now = LocalDateTime.now();
        
        heartbeatMap.forEach((nodeId, heartbeat) -> {
            if (heartbeat != null && heartbeat.getTimestamp() != null) {
                long millisSinceLastBeat = ChronoUnit.MILLIS.between(
                    heartbeat.getTimestamp(), now);
                
                if (millisSinceLastBeat > heartbeatTimeout) {
                    Long currentMissed = missedHeartbeatMap.getOrDefault(nodeId, 0L);
                    missedHeartbeatMap.put(nodeId, currentMissed + 1);
                    
                    if (currentMissed + 1 == 3) {
                        log.warn("Node {} exceeded heartbeat threshold. Missed beats: {}",
                            nodeId, currentMissed + 1);
                    }
                }
            }
        });
    }
}
