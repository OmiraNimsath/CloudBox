package com.cloudbox.service;

import com.cloudbox.config.TimeSyncProperties;
import com.cloudbox.model.HybridLogicalClock;
import com.cloudbox.model.LogicalTimestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClockSynchronizerTest {

    private ClockSynchronizer clockSynchronizer;
    private TimeSyncProperties properties;

    @BeforeEach
    void setUp() {
        properties = new TimeSyncProperties();
        clockSynchronizer = new ClockSynchronizer(properties, 1);
    }

    @Test
    void testInitialization() {
        HybridLogicalClock hlc = clockSynchronizer.getCurrentHLC();
        LogicalTimestamp logTS = clockSynchronizer.getCurrentLogicalTimestamp();

        assertNotNull(hlc);
        assertNotNull(logTS);
        assertEquals(1, hlc.getNodeId());
        assertEquals(1, logTS.getNodeId());
        assertEquals(0, logTS.getTimestamp());
    }

    @Test
    void testUpdateOnSend() {
        HybridLogicalClock before = clockSynchronizer.getCurrentHLC();
        LogicalTimestamp logBefore = clockSynchronizer.getCurrentLogicalTimestamp();

        clockSynchronizer.updateOnSend();

        HybridLogicalClock after = clockSynchronizer.getCurrentHLC();
        LogicalTimestamp logAfter = clockSynchronizer.getCurrentLogicalTimestamp();

        assertTrue(after.getPhysicalTime() >= before.getPhysicalTime());
        assertTrue(logAfter.getTimestamp() > logBefore.getTimestamp());
    }

    @Test
    void testUpdateOnReceive() {
        HybridLogicalClock remote = HybridLogicalClock.builder()
                .physicalTime(System.currentTimeMillis())
                .logicalCounter(5)
                .nodeId(2)
                .build();

        LogicalTimestamp remoteLogTS = LogicalTimestamp.builder()
                .timestamp(10)
                .nodeId(2)
                .build();

        clockSynchronizer.updateOnReceive(remote, remoteLogTS);

        LogicalTimestamp logAfter = clockSynchronizer.getCurrentLogicalTimestamp();
        assertTrue(logAfter.getTimestamp() > 10);
    }

    @Test
    void testAdjustTimeOffset_Instant() {
        properties.setClock_adjustment_strategy("instant");

        clockSynchronizer.adjustTimeOffset(50);
        assertEquals(50, clockSynchronizer.getSystemTimeOffset());

        clockSynchronizer.adjustTimeOffset(-30);
        assertEquals(-30, clockSynchronizer.getSystemTimeOffset());
    }

    @Test
    void testAdjustTimeOffset_Gradual() {
        properties.setClock_adjustment_strategy("gradual");

        clockSynchronizer.adjustTimeOffset(100);
        long offset1 = clockSynchronizer.getSystemTimeOffset();
        assertTrue(offset1 > 0);
        assertTrue(offset1 < 100);

        clockSynchronizer.adjustTimeOffset(100);
        long offset2 = clockSynchronizer.getSystemTimeOffset();
        assertTrue(offset2 > offset1); // Gradually increasing
    }

    @Test
    void testGetAdjustedPhysicalTime() {
        long before = System.currentTimeMillis();
        clockSynchronizer.adjustTimeOffset(10);
        long adjusted = clockSynchronizer.getAdjustedPhysicalTime();
        long after = System.currentTimeMillis();

        assertTrue(adjusted >= before - 10);
        assertTrue(adjusted <= after - 10);
    }

    @Test
    void testGetMetrics() {
        ClockSynchronizer.SyncMetrics metrics = clockSynchronizer.getMetrics();

        assertNotNull(metrics);
        assertEquals(1, metrics.getNodeId());
        assertNotNull(metrics.getCurrentHLC());
        assertNotNull(metrics.getLastSyncTime());
    }

    @Test
    void testConcurrentModification_ReadsDuringWrites() throws InterruptedException {
        Thread writeThread = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                clockSynchronizer.updateOnSend();
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        Thread readThread = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                HybridLogicalClock hlc = clockSynchronizer.getCurrentHLC();
                assertNotNull(hlc);
            }
        });

        writeThread.start();
        readThread.start();

        writeThread.join();
        readThread.join();

        assertNotNull(clockSynchronizer.getCurrentHLC());
    }
}
