package com.cloudbox.service;

import com.cloudbox.config.TimeSyncProperties;
import com.cloudbox.model.ClockSkewInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkewDetectorTest {

    private SkewDetector skewDetector;
    private TimeSyncProperties properties;
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        properties = new TimeSyncProperties();
        properties.setClock_skew_threshold_ms(100);

        restTemplate = mock(RestTemplate.class);
        skewDetector = new SkewDetector(properties, restTemplate, 1, 5);
    }

    @Test
    void testRecordSkew_WithinThreshold() {
        skewDetector.recordSkew(2, 50);

        ClockSkewInfo skewInfo = skewDetector.getSkewInfo(2);
        assertEquals(50, skewInfo.getSkewMillis());
        assertFalse(skewInfo.isAlertTriggered());
    }

    @Test
    void testRecordSkew_ExceedsThreshold() {
        skewDetector.recordSkew(2, 150);

        ClockSkewInfo skewInfo = skewDetector.getSkewInfo(2);
        assertEquals(150, skewInfo.getSkewMillis());
        assertTrue(skewInfo.isAlertTriggered());
        assertTrue(skewDetector.isAlertActive());
    }

    @Test
    void testRecordSkew_NegativeSkew() {
        skewDetector.recordSkew(2, -150);

        ClockSkewInfo skewInfo = skewDetector.getSkewInfo(2);
        assertEquals(-150, skewInfo.getSkewMillis());
        assertTrue(skewInfo.isAlertTriggered());
        assertTrue(skewDetector.isAlertActive());
    }

    @Test
    void testRecordSkew_AlertNormalization() {
        skewDetector.recordSkew(2, 150); // Alert triggered
        assertTrue(skewDetector.isAlertActive());

        skewDetector.recordSkew(2, 50); // Alert should be cleared
        ClockSkewInfo skewInfo = skewDetector.getSkewInfo(2);
        assertFalse(skewInfo.isAlertTriggered());
    }

    @Test
    void testGetMaxClockSkew() {
        skewDetector.recordSkew(2, 80);
        skewDetector.recordSkew(3, 120);
        skewDetector.recordSkew(4, 50);

        assertEquals(120, skewDetector.getMaxClockSkew());
    }

    @Test
    void testGetMaxClockSkew_Negative() {
        skewDetector.recordSkew(2, -80);
        skewDetector.recordSkew(3, 120);

        assertEquals(120, skewDetector.getMaxClockSkew()); // abs value
    }

    @Test
    void testGetAlertNodeCount() {
        skewDetector.recordSkew(2, 150);
        skewDetector.recordSkew(3, 50);
        skewDetector.recordSkew(4, 120);

        assertEquals(2, skewDetector.getAlertNodeCount());
    }

    @Test
    void testGetInSyncNodeCount() {
        skewDetector.recordSkew(2, 50);
        skewDetector.recordSkew(3, 150); // Alert
        skewDetector.recordSkew(4, 80);

        assertEquals(2, skewDetector.getInSyncNodeCount());
    }

    @Test
    void testGetAllSkewInfo() {
        skewDetector.recordSkew(2, 50);
        skewDetector.recordSkew(3, 80);
        skewDetector.recordSkew(4, 100);

        var skewMap = skewDetector.getAllSkewInfo();
        assertEquals(3, skewMap.size());
        assertTrue(skewMap.containsKey(2));
        assertTrue(skewMap.containsKey(3));
        assertTrue(skewMap.containsKey(4));
    }

    @Test
    void testGenerateReport() {
        skewDetector.recordSkew(2, 50);
        skewDetector.recordSkew(3, 150);

        SkewDetector.SkewReport report = skewDetector.generateReport();

        assertNotNull(report);
        assertEquals(1, report.getNodeId());
        assertEquals(150, report.getMaxClockSkew());
        assertTrue(report.isAlertActive());
        assertEquals(1, report.getInSyncNodeCount());
        assertEquals(2, report.getTotalRemoteNodes());
    }

    @Test
    void testClearSkewData() {
        skewDetector.recordSkew(2, 150);
        skewDetector.recordSkew(3, 80);

        assertEquals(2, skewDetector.getAllSkewInfo().size());
        assertTrue(skewDetector.isAlertActive());

        skewDetector.clearSkewData();

        assertEquals(0, skewDetector.getAllSkewInfo().size());
        assertFalse(skewDetector.isAlertActive());
        assertEquals(0, skewDetector.getMaxClockSkew());
    }

    @Test
    void testMultipleUpdatesPerNode() {
        skewDetector.recordSkew(2, 30);
        skewDetector.recordSkew(2, 50);
        skewDetector.recordSkew(2, 70);

        ClockSkewInfo skewInfo = skewDetector.getSkewInfo(2);
        assertEquals(70, skewInfo.getSkewMillis());
        assertEquals(70, skewInfo.getMaxSkewMillis()); // Tracked max
    }

    @Test
    void testConcurrentRecording() throws InterruptedException {
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                skewDetector.recordSkew(2, i);
            }
        });

        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                skewDetector.recordSkew(3, i);
            }
        });

        Thread t3 = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                var skewMap = skewDetector.getAllSkewInfo();
                assertNotNull(skewMap);
            }
        });

        t1.start();
        t2.start();
        t3.start();

        t1.join();
        t2.join();
        t3.join();

        assertNotNull(skewDetector.getAllSkewInfo());
    }
}
