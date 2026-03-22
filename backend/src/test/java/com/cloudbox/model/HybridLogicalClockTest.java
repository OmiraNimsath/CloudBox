package com.cloudbox.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HybridLogicalClockTest {

    private HybridLogicalClock hlc;

    @BeforeEach
    void setUp() {
        hlc = HybridLogicalClock.builder()
                .physicalTime(1000L)
                .logicalCounter(0)
                .nodeId(1)
                .build();
    }

    @Test
    void testNow() {
        long before = System.currentTimeMillis();
        HybridLogicalClock now = HybridLogicalClock.now(1);
        long after = System.currentTimeMillis();

        assertEquals(1, now.getNodeId());
        assertEquals(0, now.getLogicalCounter());
        assertTrue(now.getPhysicalTime() >= before);
        assertTrue(now.getPhysicalTime() <= after);
    }

    @Test
    void testIncrement() {
        assertEquals(0, hlc.getLogicalCounter());
        hlc.increment();
        assertEquals(1, hlc.getLogicalCounter());
        hlc.increment();
        assertEquals(2, hlc.getLogicalCounter());
    }

    @Test
    void testUpdateSend_NewPhysicalTime() {
        long oldPhysicalTime = hlc.getPhysicalTime();
        Thread.sleep(10); // Ensure time advances
        hlc.updateSend(1);

        assertTrue(hlc.getPhysicalTime() > oldPhysicalTime);
        assertEquals(0, hlc.getLogicalCounter());
    }

    @Test
    void testUpdateSend_SamePhysicalTime() {
        hlc.updateSend(1);
        long pt1 = hlc.getPhysicalTime();
        long lc1 = hlc.getLogicalCounter();

        hlc.updateSend(1);
        long pt2 = hlc.getPhysicalTime();
        long lc2 = hlc.getLogicalCounter();

        assertEquals(pt1, pt2);
        assertTrue(lc2 > lc1);
    }

    @Test
    void testUpdateReceive_NewPhysicalTime() {
        HybridLogicalClock remote = HybridLogicalClock.builder()
                .physicalTime(500L)
                .logicalCounter(5)
                .nodeId(2)
                .build();

        Thread.sleep(20);
        hlc.updateReceive(remote, 1);

        assertTrue(hlc.getPhysicalTime() > 500L);
        assertEquals(0, hlc.getLogicalCounter());
    }

    @Test
    void testUpdateReceive_RemoteTimestampAhead() {
        HybridLogicalClock remote = HybridLogicalClock.builder()
                .physicalTime(2000L)
                .logicalCounter(5)
                .nodeId(2)
                .build();

        hlc.updateReceive(remote, 1);

        assertEquals(2000L, hlc.getPhysicalTime());
        assertEquals(6L, hlc.getLogicalCounter());
    }

    @Test
    void testUpdateReceive_SamePhysicalTime() {
        HybridLogicalClock remote = HybridLogicalClock.builder()
                .physicalTime(1000L)
                .logicalCounter(3)
                .nodeId(2)
                .build();

        hlc.setLogicalCounter(2);
        hlc.updateReceive(remote, 1);

        assertEquals(1000L, hlc.getPhysicalTime());
        assertEquals(4L, hlc.getLogicalCounter()); // max(2, 3) + 1
    }

    @Test
    void testCopy() {
        HybridLogicalClock copy = hlc.copy();

        assertEquals(hlc.getPhysicalTime(), copy.getPhysicalTime());
        assertEquals(hlc.getLogicalCounter(), copy.getLogicalCounter());
        assertEquals(hlc.getNodeId(), copy.getNodeId());
    }

    @Test
    void testCompareTo_ByPhysicalTime() {
        HybridLogicalClock other = HybridLogicalClock.builder()
                .physicalTime(2000L)
                .logicalCounter(0)
                .nodeId(1)
                .build();

        assertTrue(hlc.compareTo(other) < 0);
        assertTrue(other.compareTo(hlc) > 0);
    }

    @Test
    void testCompareTo_ByLogicalCounter() {
        HybridLogicalClock other = HybridLogicalClock.builder()
                .physicalTime(1000L)
                .logicalCounter(5)
                .nodeId(1)
                .build();

        assertTrue(hlc.compareTo(other) < 0);
        assertTrue(other.compareTo(hlc) > 0);
    }

    @Test
    void testCompareTo_ByNodeId() {
        HybridLogicalClock other = HybridLogicalClock.builder()
                .physicalTime(1000L)
                .logicalCounter(0)
                .nodeId(2)
                .build();

        assertTrue(hlc.compareTo(other) < 0);
    }

    @Test
    void testCompareTo_Null() {
        assertTrue(hlc.compareTo(null) > 0);
    }
}
