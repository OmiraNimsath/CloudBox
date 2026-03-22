package com.cloudbox;

import com.cloudbox.config.TimeSyncProperties;
import com.cloudbox.model.*;
import com.cloudbox.service.ClockSynchronizer;
import com.cloudbox.service.SkewDetector;
import com.cloudbox.service.TimeSyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Time Synchronization module.
 *
 * Tests the complete workflow of time synchronization across
 * multiple components and concurrent scenarios.
 */
@SpringBootTest
@ActiveProfiles("test")
class TimeSyncIntegrationTest {

    @Autowired
    private TimeSyncService timeSyncService;

    @Autowired
    private ClockSynchronizer clockSynchronizer;

    @Autowired
    private SkewDetector skewDetector;

    @Autowired
    private TimeSyncProperties timeSyncProperties;

    @Autowired
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        skewDetector.clearSkewData();
    }

    @AfterEach
    void tearDown() {
        skewDetector.clearSkewData();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Basic Integration Tests
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void testBeansCreated() {
        assertNotNull(timeSyncService);
        assertNotNull(clockSynchronizer);
        assertNotNull(skewDetector);
        assertNotNull(timeSyncProperties);
    }

    @Test
    void testTimeSyncPropertiesLoaded() {
        assertEquals(100, timeSyncProperties.getClock_skew_threshold_ms());
        assertTrue(timeSyncProperties.isEnable_ntp());
        assertNotNull(timeSyncProperties.getNtp_server());
    }

    @Test
    void testInitialClockState() {
        HybridLogicalClock hlc = clockSynchronizer.getCurrentHLC();
        LogicalTimestamp logTS = clockSynchronizer.getCurrentLogicalTimestamp();

        assertNotNull(hlc);
        assertNotNull(logTS);
        assertEquals(0, logTS.getTimestamp());
        assertEquals(0, hlc.getLogicalCounter());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Event Flow Tests
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void testEventTimestampCreationAndMerging() {
        // Node 1 creates event
        TimeSyncService.EventTimestamp event1 = timeSyncService.createEventTimestamp();
        assertNotNull(event1);
        assertEquals(1, event1.getNodeId());
        assertTrue(event1.getLogicalTimestamp() > 0);

        // Simulate Node 2 receiving and responding
        long localLogicBefore = timeSyncService.getCurrentLogicalTimestamp().getTimestamp();
        timeSyncService.mergeRemoteTimestamp(event1);
        long localLogicAfter = timeSyncService.getCurrentLogicalTimestamp().getTimestamp();

        assertTrue(localLogicAfter > localLogicBefore);
    }

    @Test
    void testCausalEventOrdering() {
        // Create sequence of events
        TimeSyncService.EventTimestamp event1 = timeSyncService.createEventTimestamp();
        TimeSyncService.EventTimestamp event2 = timeSyncService.createEventTimestamp();
        TimeSyncService.EventTimestamp event3 = timeSyncService.createEventTimestamp();

        // Verify ordering
        assertTrue(timeSyncService.compareEventTimestamps(event1, event2) < 0);
        assertTrue(timeSyncService.compareEventTimestamps(event2, event3) < 0);
        assertTrue(timeSyncService.compareEventTimestamps(event1, event3) < 0);
    }

    @Test
    void testMultiNodeClockMerging() {
        // Node 1 does work
        timeSyncService.recordEventSend();

        // Node 2 sends message with its clock
        HybridLogicalClock remoteHLC = HybridLogicalClock.builder()
                .physicalTime(System.currentTimeMillis())
                .logicalCounter(10)
                .nodeId(2)
                .build();

        LogicalTimestamp remoteLogTS = LogicalTimestamp.builder()
                .timestamp(20)
                .nodeId(2)
                .build();

        // Node 1 receives
        timeSyncService.recordEventReceive(remoteHLC, remoteLogTS);

        LogicalTimestamp local = timeSyncService.getCurrentLogicalTimestamp();
        assertTrue(local.getTimestamp() > 20);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Clock Skew Integration Tests
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void testClockSkewDetectionWorkflow() {
        // Simulate measurements from multiple nodes
        skewDetector.recordSkew(2, 50);
        skewDetector.recordSkew(3, 80);
        skewDetector.recordSkew(4, 120); // Exceeds threshold

        TimeSyncService.TimeSyncHealth health = timeSyncService.healthCheck();

        assertEquals("DEGRADED", health.getStatus());
        assertTrue(health.isClockSkewHealthy() == false);
        assertEquals(1, health.getInSyncNodeCount());
        assertEquals(120, health.getMaxClockSkew());
    }

    @Test
    void testClockSkewAlertNormalization() {
        // Start with alert condition
        skewDetector.recordSkew(2, 150);
        assertTrue(timeSyncService.isClockSkewAlertActive());

        TimeSyncService.TimeSyncHealth health1 = timeSyncService.healthCheck();
        assertEquals("DEGRADED", health1.getStatus());

        // Normalize skew
        skewDetector.recordSkew(2, 50);
        assertFalse(timeSyncService.isClockSkewAlertActive());

        TimeSyncService.TimeSyncHealth health2 = timeSyncService.healthCheck();
        assertEquals("HEALTHY", health2.getStatus());
    }

    @Test
    void testSkewReportGeneration() {
        skewDetector.recordSkew(2, 45);
        skewDetector.recordSkew(3, 65);
        skewDetector.recordSkew(4, 95);
        skewDetector.recordSkew(5, 120); // Alert

        SkewDetector.SkewReport report = timeSyncService.getSkewReport();

        assertNotNull(report);
        assertEquals(120, report.getMaxClockSkew());
        assertTrue(report.isAlertActive());
        assertEquals(3, report.getInSyncNodeCount());
        assertEquals(4, report.getTotalRemoteNodes());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Status & Health Integration Tests
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void testComprehensiveStatusReport() {
        // Simulate activity
        timeSyncService.recordEventSend();
        skewDetector.recordSkew(2, 50);
        skewDetector.recordSkew(3, 150); // Alert

        TimeSyncStatus status = timeSyncService.getStatus();

        assertNotNull(status);
        assertEquals(1, status.getNodeId());
        assertFalse(status.isSynced()); // Alert is active
        assertEquals(150, status.getMaxClockSkew());
        assertNotNull(status.getNodeSkewMap());
        assertTrue(status.getNodeSkewMap().size() > 0);
    }

    @Test
    void testHealthCheckTransitions() {
        // Initially healthy
        TimeSyncService.TimeSyncHealth health1 = timeSyncService.healthCheck();
        assertEquals("HEALTHY", health1.getStatus());

        // Add skew within threshold
        skewDetector.recordSkew(2, 80);
        TimeSyncService.TimeSyncHealth health2 = timeSyncService.healthCheck();
        assertEquals("HEALTHY", health2.getStatus());

        // Add skew exceeding threshold
        skewDetector.recordSkew(3, 150);
        TimeSyncService.TimeSyncHealth health3 = timeSyncService.healthCheck();
        assertEquals("DEGRADED", health3.getStatus());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Concurrent Operation Tests
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void testConcurrentEventCreation() throws InterruptedException {
        int threadCount = 5;
        int eventsPerThread = 20;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger eventCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    for (int j = 0; j < eventsPerThread; j++) {
                        TimeSyncService.EventTimestamp event = timeSyncService.createEventTimestamp();
                        assertNotNull(event);
                        eventCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();

        assertEquals(threadCount * eventsPerThread, eventCount.get());
        assertNotNull(timeSyncService.getCurrentLogicalTimestamp());
    }

    @Test
    void testConcurrentSkewRecording() throws InterruptedException {
        int threadCount = 4;
        int measurements = 25;
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int nodeId = i + 2;
            new Thread(() -> {
                try {
                    for (int j = 0; j < measurements; j++) {
                        long skew = (long) (Math.random() * 200 - 100);
                        skewDetector.recordSkew(nodeId, skew);
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();

        assertEquals(4, skewDetector.getAllSkewInfo().size());
        assertNotNull(skewDetector.getMaxClockSkew());
    }

    @Test
    void testConcurrentReadsDuringWrites() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(6);
        AtomicInteger readCount = new AtomicInteger(0);

        // Writers
        for (int i = 0; i < 3; i++) {
            new Thread(() -> {
                try {
                    for (int j = 0; j < 50; j++) {
                        timeSyncService.recordEventSend();
                        Thread.sleep(1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        // Readers
        for (int i = 0; i < 3; i++) {
            new Thread(() -> {
                try {
                    for (int j = 0; j < 50; j++) {
                        HybridLogicalClock hlc = timeSyncService.getCurrentHLC();
                        assertNotNull(hlc);
                        readCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();

        assertTrue(readCount.get() > 0);
        assertNotNull(timeSyncService.getCurrentHLC());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Time Offset & Adjustment Tests
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void testTimeOffsetAdjustment() {
        long baseBefore = System.currentTimeMillis();

        clockSynchronizer.adjustTimeOffset(50);
        long adjusted1 = clockSynchronizer.getAdjustedPhysicalTime();

        clockSynchronizer.adjustTimeOffset(-30);
        long adjusted2 = clockSynchronizer.getAdjustedPhysicalTime();

        long baseAfter = System.currentTimeMillis();

        // Adjusted times should be less than current time
        assertTrue(baseBefore - 50 <= adjusted1 && adjusted1 <= baseAfter - 50);
    }

    @Test
    void testGradualClockAdjustment() {
        timeSyncProperties.setClock_adjustment_strategy("gradual");

        long offset1 = clockSynchronizer.getSystemTimeOffset();
        clockSynchronizer.adjustTimeOffset(100);
        long offset2 = clockSynchronizer.getSystemTimeOffset();

        clockSynchronizer.adjustTimeOffset(100);
        long offset3 = clockSynchronizer.getSystemTimeOffset();

        // Gradual should be increasing towards target
        assertTrue(offset2 > offset1);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Integration Scenarios
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void testFullClusterSyncScenario() {
        // Simulate cluster with 5 nodes
        int clusterSize = 5;

        // Each node measures skew with all others
        for (int i = 2; i <= clusterSize; i++) {
            long skew = (long) (Math.random() * 80 - 40); // Within threshold
            skewDetector.recordSkew(i, skew);
        }

        // Check overall cluster health
        TimeSyncService.TimeSyncHealth health = timeSyncService.healthCheck();
        assertEquals("HEALTHY", health.getStatus());
        assertEquals(clusterSize - 1, health.getInSyncNodeCount());
    }

    @Test
    void testClusterPartitionDetection() {
        // Node 2 has high skew
        skewDetector.recordSkew(2, 200);
        skewDetector.recordSkew(3, 50);
        skewDetector.recordSkew(4, 60);
        skewDetector.recordSkew(5, 210);

        SkewDetector.SkewReport report = skewDetector.generateReport();

        assertEquals(210, report.getMaxClockSkew());
        assertTrue(report.isAlertActive());
        assertEquals(2, report.getAlertNodeCount());
    }

    @Test
    void testEventOrderingAcrossCluster() {
        // Create events from multiple sources
        TimeSyncService.EventTimestamp event1 = timeSyncService.createEventTimestamp();

        // Simulate remote event
        HybridLogicalClock remoteHLC = HybridLogicalClock.builder()
                .physicalTime(System.currentTimeMillis() - 100)
                .logicalCounter(0)
                .nodeId(2)
                .build();

        TimeSyncService.EventTimestamp event2 = TimeSyncService.EventTimestamp.builder()
                .nodeId(2)
                .hlc(remoteHLC)
                .logicalTimestamp(5)
                .build();

        TimeSyncService.EventTimestamp event3 = timeSyncService.createEventTimestamp();

        // Event 2 should be ordered first (earlier physical time)
        assertTrue(timeSyncService.compareEventTimestamps(event2, event1) < 0);
        assertTrue(timeSyncService.compareEventTimestamps(event1, event3) < 0);
    }

    @Test
    void testDataConsistencyUnderLoad() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(3);

        // Event creator
        new Thread(() -> {
            try {
                for (int i = 0; i < 100; i++) {
                    timeSyncService.createEventTimestamp();
                }
            } finally {
                latch.countDown();
            }
        }).start();

        // Skew recorder
        new Thread(() -> {
            try {
                for (int i = 0; i < 100; i++) {
                    skewDetector.recordSkew((i % 3) + 2, (i * 11) % 200 - 100);
                }
            } finally {
                latch.countDown();
            }
        }).start();

        // Status reporter
        new Thread(() -> {
            try {
                for (int i = 0; i < 100; i++) {
                    TimeSyncStatus status = timeSyncService.getStatus();
                    assertNotNull(status);
                }
            } finally {
                latch.countDown();
            }
        }).start();

        latch.await();

        // Verify consistency
        TimeSyncStatus finalStatus = timeSyncService.getStatus();
        assertNotNull(finalStatus);
        assertNotNull(finalStatus.getNodeSkewMap());
    }
}
