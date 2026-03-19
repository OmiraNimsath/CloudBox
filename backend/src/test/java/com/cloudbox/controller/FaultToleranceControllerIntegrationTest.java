package com.cloudbox.controller;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.cloudbox.model.ApiResponse;
import com.cloudbox.model.FaultStatus;
import com.cloudbox.model.NodeHealth;
import com.cloudbox.model.RecoveryTask;
import com.cloudbox.service.FailureDetectionService;
import com.cloudbox.service.FaultToleranceManager;
import com.cloudbox.service.HeartbeatMonitor;
import com.cloudbox.service.RecoveryManager;

/**
 * Integration tests for FaultToleranceController.
 */
@ExtendWith(MockitoExtension.class)
class FaultToleranceControllerIntegrationTest {
    
    @Mock
    private FaultToleranceManager faultToleranceManager;
    
    @Mock
    private HeartbeatMonitor heartbeatMonitor;
    
    @Mock
    private FailureDetectionService failureDetectionService;
    
    @Mock
    private RecoveryManager recoveryManager;
    
    private FaultToleranceController controller;
    
    @BeforeEach
    void setUp() {
        controller = new FaultToleranceController(
            faultToleranceManager,
            heartbeatMonitor,
            failureDetectionService,
            recoveryManager
        );
    }
    
    @Test
    void testGetStatusReturnsValidFaultStatus() {
        FaultStatus status = FaultStatus.builder()
            .timestamp(LocalDateTime.now())
            .clusterState("HEALTHY")
            .totalNodes(5)
            .healthyNodes(5)
            .failedNodes(0)
            .hasQuorum(true)
            .build();
        
        when(faultToleranceManager.getFaultStatus()).thenReturn(status);
        
        ResponseEntity<ApiResponse<FaultStatus>> response = controller.getStatus();
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isSuccess());
        assertEquals("HEALTHY", response.getBody().getData().getClusterState());
    }
    
    @Test
    void testGetNodeHealthReturnsValidHealth() {
        NodeHealth health = NodeHealth.builder()
            .nodeId("node-1")
            .alive(true)
            .status("HEALTHY")
            .build();
        
        when(failureDetectionService.getNodeHealth("node-1")).thenReturn(health);
        
        ResponseEntity<ApiResponse<NodeHealth>> response = controller.getNodeHealth("node-1");
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isSuccess());
        assertEquals("node-1", response.getBody().getData().getNodeId());
    }
    
    @Test
    void testGetNodeHealthReturns404ForUnknownNode() {
        when(failureDetectionService.getNodeHealth("unknown")).thenReturn(null);
        
        ResponseEntity<ApiResponse<NodeHealth>> response = controller.getNodeHealth("unknown");
        
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
    
    @Test
    void testGetAllNodesReturnsHealthMap() {
        Map<String, NodeHealth> nodeMap = new HashMap<>();
        nodeMap.put("node-1", NodeHealth.builder().nodeId("node-1").alive(true).build());
        nodeMap.put("node-2", NodeHealth.builder().nodeId("node-2").alive(true).build());
        
        when(failureDetectionService.getAllNodeHealth()).thenReturn(nodeMap);
        
        ResponseEntity<ApiResponse<Map<String, NodeHealth>>> response = controller.getAllNodes();
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isSuccess());
        assertEquals(2, response.getBody().getData().size());
    }
    
    @Test
    void testGetFailedNodesReturnsEmptyList() {
        when(failureDetectionService.getFailedNodes()).thenReturn(new ArrayList<>());
        
        ResponseEntity<ApiResponse<List<String>>> response = controller.getFailedNodes();
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().getData().isEmpty());
    }
    
    @Test
    void testGetHealthyNodesReturnsNodeList() {
        List<String> healthyNodes = Arrays.asList("node-1", "node-2", "node-3");
        when(failureDetectionService.getHealthyNodes()).thenReturn(healthyNodes);
        
        ResponseEntity<ApiResponse<List<String>>> response = controller.getHealthyNodes();
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(3, response.getBody().getData().size());
    }
    
    @Test
    void testGetQuorumStatusReturnsStatus() {
        FaultStatus status = FaultStatus.builder()
            .hasQuorum(true)
            .clusterState("HEALTHY")
            .build();
        
        when(failureDetectionService.hasQuorum()).thenReturn(true);
        when(failureDetectionService.getHealthyNodeCount()).thenReturn(5);
        when(faultToleranceManager.getFaultStatus()).thenReturn(status);
        
        ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.getQuorumStatus();
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().getData().get("hasQuorum"));
    }
    
    @Test
    void testInitiateRecoveryCreatesTask() {
        Map<String, String> request = new HashMap<>();
        request.put("failedNodeId", "node-1");
        
        when(recoveryManager.initiateRecovery("node-1")).thenReturn("recovery-123");
        
        ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.initiateRecovery(request);
        
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("recovery-123", response.getBody().getData().get("recoveryId"));
    }
    
    @Test
    void testInitiateRecoveryMissingNodeIdReturnsBadRequest() {
        Map<String, String> request = new HashMap<>();
        
        ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.initiateRecovery(request);
        
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
    }
    
    @Test
    void testGetRecoveryTaskReturnsTask() {
        RecoveryTask task = RecoveryTask.builder()
            .recoveryId("recovery-123")
            .failedNodeId("node-1")
            .status("PENDING")
            .build();
        
        when(recoveryManager.getRecoveryTask("recovery-123")).thenReturn(task);
        
        ResponseEntity<ApiResponse<RecoveryTask>> response = controller.getRecoveryTask("recovery-123");
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("recovery-123", response.getBody().getData().getRecoveryId());
    }
    
    @Test
    void testEnableFaultToleranceReturnsOk() {
        ResponseEntity<ApiResponse<Map<String, String>>> response = controller.enable();
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("ENABLED", response.getBody().getData().get("status"));
        verify(faultToleranceManager).enable();
    }
    
    @Test
    void testDisableFaultToleranceReturnsOk() {
        ResponseEntity<ApiResponse<Map<String, String>>> response = controller.disable();
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("DISABLED", response.getBody().getData().get("status"));
        verify(faultToleranceManager).disable();
    }
    
    @Test
    void testIsEnabledReturnsStatus() {
        when(faultToleranceManager.isEnabled()).thenReturn(true);
        
        ResponseEntity<ApiResponse<Map<String, Boolean>>> response = controller.isEnabled();
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().getData().get("enabled"));
    }
}
