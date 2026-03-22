package com.cloudbox.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VectorClockTest {

    private VectorClock vc1;
    private VectorClock vc2;

    @BeforeEach
    void setUp() {
        vc1 = VectorClock.builder()
                .clock(new HashMap<>(Map.of(1, 0L, 2, 0L)))
                .build();

        vc2 = VectorClock.builder()
                .clock(new HashMap<>(Map.of(1, 0L, 2, 0L)))
                .build();
    }

    @Test
    void testIncrement() {
        vc1.increment(1);
        assertEquals(1L, vc1.getClock().get(1));

        vc1.increment(1);
        assertEquals(2L, vc1.getClock().get(1));

        vc1.increment(2);
        assertEquals(1L, vc1.getClock().get(2));
    }

    @Test
    void testUpdate_ElementWiseMax() {
        vc1.getClock().put(1, 3L);
        vc1.getClock().put(2, 1L);

        vc2.getClock().put(1, 1L);
        vc2.getClock().put(2, 4L);

        vc1.update(vc2, 1);

        assertEquals(3L, vc1.getClock().get(1)); // max(3, 1) = 3
        assertEquals(4L, vc1.getClock().get(2)); // max(1, 4) = 4
        assertEquals(4L, vc1.getClock().get(1)); // Incremented for this node after update
    }

    @Test
    void testHappensBefore_True() {
        vc1.getClock().put(1, 1L);
        vc1.getClock().put(2, 0L);

        vc2.getClock().put(1, 2L);
        vc2.getClock().put(2, 0L);

        assertTrue(vc1.happensBefore(vc2));
        assertFalse(vc2.happensBefore(vc1));
    }

    @Test
    void testHappensBefore_False_Concurrent() {
        vc1.getClock().put(1, 2L);
        vc1.getClock().put(2, 0L);

        vc2.getClock().put(1, 0L);
        vc2.getClock().put(2, 1L);

        assertFalse(vc1.happensBefore(vc2));
        assertFalse(vc2.happensBefore(vc1));
    }

    @Test
    void testIsConcurrent() {
        vc1.getClock().put(1, 2L);
        vc1.getClock().put(2, 0L);

        vc2.getClock().put(1, 0L);
        vc2.getClock().put(2, 1L);

        assertTrue(vc1.isConcurrent(vc2));
        assertTrue(vc2.isConcurrent(vc1));
    }

    @Test
    void testIsConcurrent_False_NotConcurrent() {
        vc1.getClock().put(1, 1L);
        vc1.getClock().put(2, 0L);

        vc2.getClock().put(1, 2L);
        vc2.getClock().put(2, 0L);

        assertFalse(vc1.isConcurrent(vc2));
    }

    @Test
    void testCopy() {
        vc1.getClock().put(1, 5L);
        vc1.getClock().put(2, 3L);

        VectorClock copy = vc1.copy();

        assertEquals(vc1.getClock(), copy.getClock());
        assertNotSame(vc1.getClock(), copy.getClock()); // Different objects
    }

    @Test
    void testUpdate_WithNull() {
        vc1.getClock().put(1, 5L);
        vc1.update(null, 1);

        assertEquals(6L, vc1.getClock().get(1)); // Just incremented
    }
}
