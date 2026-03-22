package com.cloudbox;

import com.cloudbox.controller.TimeSyncController;
import com.cloudbox.model.*;
import com.cloudbox.service.SkewDetector;
import com.cloudbox.service.TimeSyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Time Synchronization REST API.
 *
 * Tests all endpoints and their integration with backend services.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TimeSyncControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TimeSyncService timeSyncService;

    @Autowired
    private SkewDetector skewDetector;

    @BeforeEach
    void setUp() {
        skewDetector.clearSkewData();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Status & Health Endpoints
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void testGetTimeSyncStatus() throws Exception {
        mockMvc.perform(get("/api/timesync/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.nodeId").value(1))
                .andExpect(jsonPath("$.data.synced").exists())
                .andExpect(jsonPath("$.data.hlcPhysicalTime").exists())
                .andExpect(jsonPath("$.data.hlcLogicalCounter").exists())
                .andExpect(jsonPath("$.data.logicalTimestamp").exists());
    }

    @Test
    void testGetHealth_Healthy() throws Exception {
        mockMvc.perform(get("/api/timesync/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("HEALTHY"))
                .andExpect(jsonPath("$.data.clockSkewHealthy").value(true));
    }

    @Test
    void testGetHealth_Degraded() throws Exception {
        skewDetector.recordSkew(2, 150);

        mockMvc.perform(get("/api/timesync/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("DEGRADED"))
                .andExpect(jsonPath("$.data.clockSkewHealthy").value(false));
    }

    @Test
    void testGetModuleInfo() throws Exception {
        mockMvc.perform(get("/api/timesync/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.module").value("TimeSynchronization"))
                .andExpect(jsonPath("$.data.status").exists())
                .andExpect(jsonPath("$.data.synced").exists())
                .andExpect(jsonPath("$.data.maxClockSkew").exists())
                .andExpect(jsonPath("$.data.inSyncNodes").exists())
                .andExpect(jsonPath("$.data.timestamp").exists());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Time Retrieval Endpoints
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void testGetCurrentTime() throws Exception {
        long before = System.currentTimeMillis();

        mockMvc.perform(get("/api/timesync/time"))
                .andExpect(status().isOk())
                .andExpect(content().string(notNullValue()));

        long after = System.currentTimeMillis();
    }

    @Test
    void testGetCurrentHLC() throws Exception {
        mockMvc.perform(get("/api/timesync/hlc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.nodeId").value(1))
                .andExpect(jsonPath("$.data.physicalTime").exists())
                .andExpect(jsonPath("$.data.logicalCounter").exists());
    }

    @Test
    void testGetLogicalTimestamp() throws Exception {
        mockMvc.perform(get("/api/timesync/logical-timestamp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.nodeId").value(1))
                .andExpect(jsonPath("$.data.timestamp").exists());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Clock Skew Endpoints
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void testGetClockSkewInfo() throws Exception {
        skewDetector.recordSkew(2, 50);
        skewDetector.recordSkew(3, 80);

        mockMvc.perform(get("/api/timesync/skew"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasKey("2")))
                .andExpect(jsonPath("$.data", hasKey("3")))
                .andExpect(jsonPath("$.data['2'].nodeId").value(2))
                .andExpect(jsonPath("$.data['2'].skewMillis").value(50))
                .andExpect(jsonPath("$.data['3'].nodeId").value(3))
                .andExpect(jsonPath("$.data['3'].skewMillis").value(80));
    }

    @Test
    void testGetMaxClockSkew() throws Exception {
        skewDetector.recordSkew(2, 75);
        skewDetector.recordSkew(3, 120);

        mockMvc.perform(get("/api/timesync/skew/max"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(120));
    }

    @Test
    void testIsClockSkewAlertActive_False() throws Exception {
        skewDetector.recordSkew(2, 50);

        mockMvc.perform(get("/api/timesync/skew/alert-active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(false));
    }

    @Test
    void testIsClockSkewAlertActive_True() throws Exception {
        skewDetector.recordSkew(2, 150);

        mockMvc.perform(get("/api/timesync/skew/alert-active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    void testGetInSyncNodeCount() throws Exception {
        skewDetector.recordSkew(2, 50);
        skewDetector.recordSkew(3, 150); // Alert
        skewDetector.recordSkew(4, 80);

        mockMvc.perform(get("/api/timesync/skew/in-sync-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(2));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Report Endpoints
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void testGetSkewReport() throws Exception {
        skewDetector.recordSkew(2, 50);
        skewDetector.recordSkew(3, 150);
        skewDetector.recordSkew(4, 80);

        mockMvc.perform(get("/api/timesync/skew-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.nodeId").value(1))
                .andExpect(jsonPath("$.data.maxClockSkew").value(150))
                .andExpect(jsonPath("$.data.alertActive").value(true))
                .andExpect(jsonPath("$.data.inSyncNodeCount").value(2))
                .andExpect(jsonPath("$.data.totalRemoteNodes").value(3))
                .andExpect(jsonPath("$.data.skewDetails", hasSize(3)));
    }

    @Test
    void testGetSyncMetrics() throws Exception {
        timeSyncService.recordEventSend();

        mockMvc.perform(get("/api/timesync/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.nodeId").value(1))
                .andExpect(jsonPath("$.data.currentHLC").exists())
                .andExpect(jsonPath("$.data.currentLogicalTimestamp").exists())
                .andExpect(jsonPath("$.data.lastSyncTime").exists());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Diagnostic Endpoints
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void testClearSkewData() throws Exception {
        skewDetector.recordSkew(2, 150);

        mockMvc.perform(post("/api/timesync/clear-skew-data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("Skew data cleared successfully"));

        // Verify data was cleared
        mockMvc.perform(get("/api/timesync/skew/max"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(0));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Error Handling Tests
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void testInvalidEndpoint() throws Exception {
        mockMvc.perform(get("/api/timesync/invalid-endpoint"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testMethodNotAllowed() throws Exception {
        mockMvc.perform(post("/api/timesync/status"))
                .andExpect(status().isMethodNotAllowed());
    }

    // ──────────────────────────────────────────────────────────────────────
    // API Response Consistency Tests
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void testAllSuccessResponsesHaveConsistentStructure() throws Exception {
        mockMvc.perform(get("/api/timesync/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.data").exists());

        mockMvc.perform(get("/api/timesync/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.data").exists());

        mockMvc.perform(get("/api/timesync/skew/max"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.data").exists());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Multi-step Workflows
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void testCompleteClockSkewAlertingWorkflow() throws Exception {
        // Step 1: Verify initially healthy
        mockMvc.perform(get("/api/timesync/health"))
                .andExpect(jsonPath("$.data.status").value("HEALTHY"));

        // Step 2: Record skew within threshold
        skewDetector.recordSkew(2, 50);
        mockMvc.perform(get("/api/timesync/health"))
                .andExpect(jsonPath("$.data.status").value("HEALTHY"));

        // Step 3: Record skew exceeding threshold
        skewDetector.recordSkew(3, 150);
        mockMvc.perform(get("/api/timesync/health"))
                .andExpect(jsonPath("$.data.status").value("DEGRADED"));

        // Step 4: Check alert endpoints
        mockMvc.perform(get("/api/timesync/skew/alert-active"))
                .andExpect(jsonPath("$.data").value(true));

        // Step 5: Get detailed report
        mockMvc.perform(get("/api/timesync/skew-report"))
                .andExpect(jsonPath("$.data.alertActive").value(true))
                .andExpect(jsonPath("$.data.alertNodeCount", greaterThan(0)));
    }

    @Test
    void testCompleteMonitoringWorkflow() throws Exception {
        // Record various skew measurements
        skewDetector.recordSkew(2, 45);
        skewDetector.recordSkew(3, 65);
        skewDetector.recordSkew(4, 85);
        skewDetector.recordSkew(5, 110);

        // Get overall status
        mockMvc.perform(get("/api/timesync/status"))
                .andExpect(jsonPath("$.data.maxClockSkew").value(110))
                .andExpect(jsonPath("$.data.nodeSkewMap", hasKey("2")));

        // Get skew info
        mockMvc.perform(get("/api/timesync/skew"))
                .andExpect(jsonPath("$.data", hasKey("2")))
                .andExpect(jsonPath("$.data", hasKey("3")))
                .andExpect(jsonPath("$.data", hasKey("4")))
                .andExpect(jsonPath("$.data", hasKey("5")));

        // Get comprehensive report
        mockMvc.perform(get("/api/timesync/skew-report"))
                .andExpect(jsonPath("$.data.maxClockSkew").value(110))
                .andExpect(jsonPath("$.data.inSyncNodeCount").value(3));
    }

    @Test
    void testConcurrentAPIRequests() throws Exception {
        // Simulate concurrent requests to different endpoints
        Thread t1 = new Thread(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    mockMvc.perform(get("/api/timesync/status"))
                            .andExpect(status().isOk());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    mockMvc.perform(get("/api/timesync/health"))
                            .andExpect(status().isOk());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread t3 = new Thread(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    mockMvc.perform(get("/api/timesync/skew/max"))
                            .andExpect(status().isOk());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        t1.start();
        t2.start();
        t3.start();

        t1.join();
        t2.join();
        t3.join();
    }
}
