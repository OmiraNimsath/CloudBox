package com.cloudbox.service;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cloudbox.model.HeartbeatInfo;

/**
 * Unit tests for HeartbeatMonitor implementation.
 */
@ExtendWith(MockitoExtension.class)
class HeartbeatMonitorTest {
    
    private HeartbeatMonitor heartbeatMonitor;
    
    @BeforeEach
    void setUp() {
        heartbeatMonitor = new HeartbeatMonitorImpl();
    }
    
    @Test
    void testMonitoringInitiallyInactive() {
        assertFalse(heartbeatMonitor.isMonitoring());
    }
    
    @Test
    void testStartMonitoringActivatesMonitor() {
        heartbeatMonitor.startMonitoring();
        assertTrue(heartbeatMonitor.isMonitoring());
    }
    
    @Test
    void testStopMonitoringDeactivatesMonitor() {
        heartbeatMonitor.startMonitoring();
        heartbeatMonitor.stopMonitoring();
        assertFalse(heartbeatMonitor.isMonitoring());
    }
    
    @Test
    void testRecordHeartbeatStoresData() {
        HeartbeatInfo heartbeat = HeartbeatInfo.builder()
            .nodeId("node-1")
            .timestamp(LocalDateTime.now())
            .isLeader(true)
            .clusterSize(5)
            .reachableNodes(5)
            .partitioned(false)
            .uptime(1000)
            .build();
        
        heartbeatMonitor.recordHeartbeat(heartbeat);
        
        HeartbeatInfo recorded = heartbeatMonitor.getLastHeartbeat("node-1");
        assertNotNull(recorded);
        assertEquals("node-1", recorded.getNodeId());
        assertTrue(recorded.isLeader());
    }
    
    @Test
    void testGetLastHeartbeatReturnsNullForUnknownNode() {
        assertNull(heartbeatMonitor.getLastHeartbeat("unknown-node"));
    }
    
    @Test
    void testGetAllHeartbeatsReturnsEmptyInitially() {
        Map<String, HeartbeatInfo> heartbeats = heartbeatMonitor.getAllHeartbeats();
        assertTrue(heartbeats.isEmpty());
    }
    
    @Test
    void testMissedHeartbeatsStartAtZero() {
        HeartbeatInfo heartbeat = HeartbeatInfo.builder()
            .nodeId("node-1")
            .timestamp(LocalDateTime.now())
            .isLeader(false)
            .clusterSize(5)
            .reachableNodes(5)
            .partitioned(false)
            .uptime(0)
            .build();
        
        heartbeatMonitor.recordHeartbeat(heartbeat);
        
        assertEquals(0, heartbeatMonitor.getMissedHeartbeats("node-1"));
    }
    
    @Test
    void testResetMissedHeartbeatsClearsMissed() {
        HeartbeatInfo heartbeat = HeartbeatInfo.builder()
            .nodeId("node-1")
            .timestamp(LocalDateTime.now().minusSeconds(20))
            .isLeader(false)
            .clusterSize(5)
            .reachableNodes(5)
            .partitioned(false)
            .uptime(0)
            .build();
        
        heartbeatMonitor.recordHeartbeat(heartbeat);
        heartbeatMonitor.resetMissedHeartbeats("node-1");
        
        assertEquals(0, heartbeatMonitor.getMissedHeartbeats("node-1"));
    }
    
    @Test
    void testIsNodeAliveReturnsTrueForRecentHeartbeat() {
        HeartbeatInfo heartbeat = HeartbeatInfo.builder()
            .nodeId("node-1")
            .timestamp(LocalDateTime.now())
            .isLeader(false)
            .clusterSize(5)
            .reachableNodes(5)
            .partitioned(false)
            .uptime(0)
            .build();
        
        heartbeatMonitor.recordHeartbeat(heartbeat);
        
        assertTrue(heartbeatMonitor.isNodeAlive("node-1"));
    }
}
