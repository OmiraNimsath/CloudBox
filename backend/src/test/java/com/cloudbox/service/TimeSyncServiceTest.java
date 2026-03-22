package com.cloudbox.service;

import com.cloudbox.config.TimeSyncProperties;
import com.cloudbox.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TimeSyncServiceTest {

    private TimeSyncService timeSyncService;
    private ClockSynchronizer clockSynchronizer;
    private SkewDetector skewDetector;
    private TimeSyncProperties properties;

    @BeforeEach
    void setUp() {
        properties = new TimeSyncProperties();
        properties.setClock_skew_threshold_ms(100);

        RestTemplate restTemplate = mock(RestTemplate.class);
        clockSynchronizer = new ClockSynchronizer(properties, 1);
        skewDetector = new SkewDetector(properties, restTemplate, 1, 5);

        timeSyncService = new TimeSyncService(clockSynchronizer, skewDetector, 1);
    }

    @Test
    void testGetCurrentHLC() {
        HybridLogicalClock hlc = timeSyncService.getCurrentHLC();

        assertNotNull(hlc);
        assertEquals(1, hlc.getNodeId());
    }

    @Test
    void testGetCurrentLogicalTimestamp() {
        LogicalTimestamp ts = timeSyncService.getCurrentLogicalTimestamp();

        assertNotNull(ts);
        assertEquals(1, ts.getNodeId());
    }

    @Test
    void testGetCurrentTimestamp() {
        long ts = timeSyncService.getCurrentTimestamp();

        assertTrue(ts > 0);
        assertTrue(ts <= System.currentTimeMillis());
    }

    @Test
    void testRecordEventSend() {
        HybridLogicalClock before = timeSyncService.getCurrentHLC();

        timeSyncService.recordEventSend();

        HybridLogicalClock after = timeSyncService.getCurrentHLC();
        assertTrue(after.getPhysicalTime() >= before.getPhysicalTime());
    }

    @Test
    void testRecordEventReceive() {
        HybridLogicalClock remote = HybridLogicalClock.builder()
                .physicalTime(System.currentTimeMillis())
                .logicalCounter(5)
                .nodeId(2)
                .build();

        LogicalTimestamp remoteTS = LogicalTimestamp.builder()
                .timestamp(10)
                .nodeId(2)
                .build();

        timeSyncService.recordEventReceive(remote, remoteTS);

        LogicalTimestamp localTS = timeSyncService.getCurrentLogicalTimestamp();
        assertTrue(localTS.getTimestamp() > 10);
    }

    @Test
    void testCompareHLC() {
        HybridLogicalClock hlc1 = HybridLogicalClock.builder()
                .physicalTime(1000)
                .logicalCounter(0)
                .nodeId(1)
                .build();

        HybridLogicalClock hlc2 = HybridLogicalClock.builder()
                .physicalTime(2000)
                .logicalCounter(0)
                .nodeId(1)
                .build();

        assertTrue(timeSyncService.compareHLC(hlc1, hlc2) < 0);
        assertTrue(timeSyncService.compareHLC(hlc2, hlc1) > 0);
    }

    @Test
    void testHappensBefore() {
        LogicalTimestamp ts1 = LogicalTimestamp.builder()
                .timestamp(5)
                .nodeId(1)
                .build();

        LogicalTimestamp ts2 = LogicalTimestamp.builder()
                .timestamp(10)
                .nodeId(1)
                .build();

        assertTrue(timeSyncService.happensBefore(ts1, ts2));
        assertFalse(timeSyncService.happensBefore(ts2, ts1));
    }

    @Test
    void testGetMaxClockSkew() {
        skewDetector.recordSkew(2, 80);
        skewDetector.recordSkew(3, 120);

        assertEquals(120, timeSyncService.getMaxClockSkew());
    }

    @Test
    void testIsClockSkewAlertActive() {
        assertFalse(timeSyncService.isClockSkewAlertActive());

        skewDetector.recordSkew(2, 150);
        assertTrue(timeSyncService.isClockSkewAlertActive());
    }

    @Test
    void testGetInSyncNodeCount() {
        skewDetector.recordSkew(2, 50);
        skewDetector.recordSkew(3, 80);
        skewDetector.recordSkew(4, 150); // Alert

        assertEquals(2, timeSyncService.getInSyncNodeCount());
    }

    @Test
    void testGetStatus() {
        skewDetector.recordSkew(2, 50);

        TimeSyncStatus status = timeSyncService.getStatus();

        assertNotNull(status);
        assertEquals(1, status.getNodeId());
        assertFalse(status.isSynced() == skewDetector.isAlertActive());
        assertNotNull(status.getNodeSkewMap());
    }

    @Test
    void testHealthCheck_Healthy() {
        skewDetector.recordSkew(2, 50);

        TimeSyncService.TimeSyncHealth health = timeSyncService.healthCheck();

        assertEquals("HEALTHY", health.getStatus());
        assertTrue(health.isClockSkewHealthy());
    }

    @Test
    void testHealthCheck_Degraded() {
        skewDetector.recordSkew(2, 150);

        TimeSyncService.TimeSyncHealth health = timeSyncService.healthCheck();

        assertEquals("DEGRADED", health.getStatus());
        assertFalse(health.isClockSkewHealthy());
    }

    @Test
    void testCreateEventTimestamp() {
        TimeSyncService.EventTimestamp eventTS = timeSyncService.createEventTimestamp();

        assertNotNull(eventTS);
        assertEquals(1, eventTS.getNodeId());
        assertNotNull(eventTS.getHlc());
        assertTrue(eventTS.getLogicalTimestamp() > 0);
    }

    @Test
    void testMergeRemoteTimestamp() {
        HybridLogicalClock remoteHLC = HybridLogicalClock.builder()
                .physicalTime(System.currentTimeMillis())
                .logicalCounter(5)
                .nodeId(2)
                .build();

        TimeSyncService.EventTimestamp remoteTS = TimeSyncService.EventTimestamp.builder()
                .nodeId(2)
                .hlc(remoteHLC)
                .logicalTimestamp(10)
                .build();

        LogicalTimestamp before = timeSyncService.getCurrentLogicalTimestamp();
        timeSyncService.mergeRemoteTimestamp(remoteTS);
        LogicalTimestamp after = timeSyncService.getCurrentLogicalTimestamp();

        assertTrue(after.getTimestamp() > before.getTimestamp());
    }

    @Test
    void testCompareEventTimestamps() {
        TimeSyncService.EventTimestamp ts1 = TimeSyncService.EventTimestamp.builder()
                .nodeId(1)
                .hlc(HybridLogicalClock.builder()
                        .physicalTime(1000)
                        .logicalCounter(0)
                        .nodeId(1)
                        .build())
                .logicalTimestamp(1)
                .build();

        TimeSyncService.EventTimestamp ts2 = TimeSyncService.EventTimestamp.builder()
                .nodeId(2)
                .hlc(HybridLogicalClock.builder()
                        .physicalTime(2000)
                        .logicalCounter(0)
                        .nodeId(2)
                        .build())
                .logicalTimestamp(2)
                .build();

        assertTrue(timeSyncService.compareEventTimestamps(ts1, ts2) < 0);
    }

    @Test
    void testClearSkewData() {
        skewDetector.recordSkew(2, 150);
        assertTrue(timeSyncService.isClockSkewAlertActive());

        timeSyncService.clearSkewData();

        assertFalse(timeSyncService.isClockSkewAlertActive());
        assertEquals(0, timeSyncService.getMaxClockSkew());
    }
}
