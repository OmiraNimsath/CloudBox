package com.cloudbox.model;

import java.time.LocalDateTime;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a recovery task for a failed node.
 * Tracks recovery status, data to be recovered, and progress.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecoveryTask {
    private String recoveryId;
    private String failedNodeId;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String status; // PENDING, IN_PROGRESS, COMPLETED, FAILED
    private String sourceNodeId; // node providing the recovery data
    private long totalDataSize; // bytes
    private long dataRecovered; // bytes
    private double progressPercentage;
    private List<String> filesBeingRecovered;
    private String errorMessage; // null if successful
    private int retryCount;
    private int maxRetries;
}
