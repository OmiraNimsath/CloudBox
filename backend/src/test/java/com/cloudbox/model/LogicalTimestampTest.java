package com.cloudbox.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogicalTimestampTest {

    private LogicalTimestamp timestamp;

    @BeforeEach
    void setUp() {
        timestamp = LogicalTimestamp.builder()
                .timestamp(0)
                .nodeId(1)
                .build();
    }

    @Test
    void testIncrement() {
        assertEquals(0, timestamp.getTimestamp());
        timestamp.increment();
        assertEquals(1, timestamp.getTimestamp());
        timestamp.increment();
        assertEquals(2, timestamp.getTimestamp());
    }

    @Test
    void testUpdate_WithRemoteTimestampAhead() {
        LogicalTimestamp remote = LogicalTimestamp.builder()
                .timestamp(10)
                .nodeId(2)
                .build();

        timestamp.update(remote);

        assertEquals(11, timestamp.getTimestamp());
    }

    @Test
    void testUpdate_WithRemoteTimestampBehind() {
        timestamp.setTimestamp(15);
        LogicalTimestamp remote = LogicalTimestamp.builder()
                .timestamp(5)
                .nodeId(2)
                .build();

        timestamp.update(remote);

        assertEquals(16, timestamp.getTimestamp());
    }

    @Test
    void testUpdate_WithNull() {
        timestamp.setTimestamp(5);
        timestamp.update(null);

        assertEquals(6, timestamp.getTimestamp());
    }

    @Test
    void testNext() {
        timestamp.setTimestamp(10);
        LogicalTimestamp next = timestamp.next();

        assertEquals(11, next.getTimestamp());
        assertEquals(1, next.getNodeId());
        assertEquals(10, timestamp.getTimestamp()); // Original unchanged
    }

    @Test
    void testCompareTo_SmallerTimestamp() {
        LogicalTimestamp other = LogicalTimestamp.builder()
                .timestamp(5)
                .nodeId(2)
                .build();

        assertTrue(timestamp.compareTo(other) < 0);
    }

    @Test
    void testCompareTo_LargerTimestamp() {
        timestamp.setTimestamp(10);
        LogicalTimestamp other = LogicalTimestamp.builder()
                .timestamp(5)
                .nodeId(2)
                .build();

        assertTrue(timestamp.compareTo(other) > 0);
    }

    @Test
    void testCompareTo_EqualTimestamps_DifferentNodeIds() {
        LogicalTimestamp other = LogicalTimestamp.builder()
                .timestamp(0)
                .nodeId(2)
                .build();

        assertTrue(timestamp.compareTo(other) < 0);
    }

    @Test
    void testCompareTo_Null() {
        assertTrue(timestamp.compareTo(null) > 0);
    }
}
