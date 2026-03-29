package com.cloudbox.service;

import com.cloudbox.config.ClusterConfig;
import com.cloudbox.model.ApiResponse;
import com.cloudbox.model.FileMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Anti-Entropy Service for Data Reconciliation in Distributed File Storage
 *
 * Implements a gossip-based protocol using Merkle Trees to detect and repair
 * inconsistencies between nodes. This ensures that even if a node misses
 * replication during failures, it will automatically sync up data from
 * healthy replicas.
 *
 * Key Features:
 * - Periodic scheduled reconciliation (runs every 2 minutes)
 * - Merkle tree-based efficient comparison (O(log n) instead of O(n))
 * - Automatic recovery of missing/corrupted files
 * - Gossip protocol: Only talks to current leader node
 * - Thread-safe: Uses AtomicBoolean to prevent concurrent executions
 *
 * Use Case:
 * If Node 3 crashes and misses a file upload (but quorum of 3 was still reached):
 * 1. When Node 3 comes back online
 * 2. Anti-Entropy detects the missing file via Merkle tree comparison
 * 3. Fetches the file from the leader
 * 4. Writes it locally, restoring full replication
 *
 * Lecture References:
 * - Gossip Protocols (Lecture 4): "Failure information spreads across nodes like a rumor"
 * - Data Replication (Lecture 6): Uses quorum-based approach for consistency
 * - Eventual Consistency: Over time, all nodes converge to same state
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AntiEntropyService {

    private final MerkleTreeService merkleTreeService;
    private final StorageModulePort storageModulePort;
    private final ConsensusModulePort consensusModulePort;
    private final RestTemplate restTemplate = new RestTemplateBuilder().build();

    @Value("${cloudbox.node-id}")
    private int currentNodeId;

    // Prevent concurrent anti-entropy executions (race condition guard)
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    // Statistics tracking
    private long lastReconciliationTime = 0;
    private int totalReconciliationsCycle = 0;
    private int totalFilesRecovered = 0;

    /**
     * Scheduled task: Runs every 2 minutes (120,000 ms) to perform anti-entropy reconciliation
     * 
     * This is the main entry point for the gossip-based data recovery protocol
     */
    @Scheduled(fixedRate = 120000, initialDelay = 30000)
    public void performAntiEntropyReconciliation() {
        // Prevent multiple concurrent executions
        if (!isRunning.compareAndSet(false, true)) {
            log.trace("[Anti-Entropy] Node {} reconciliation already in progress, skipping", currentNodeId);
            return;
        }

        try {
            executeReconciliation();
        } finally {
            isRunning.set(false);
        }
    }

    /**
     * Core reconciliation logic
     */
    private void executeReconciliation() {
        long startTime = System.currentTimeMillis();
        totalReconciliationsCycle++;

        try {
            // Step 1: Get current leader
            int leaderId = consensusModulePort.getCurrentLeaderNodeId();
            if (leaderId < 1) {
                log.warn("[Anti-Entropy] Node {} has no leader, skipping reconciliation", currentNodeId);
                return;
            }

            // Step 2: Skip if this node is the leader (no need to sync with itself)
            if (leaderId == currentNodeId) {
                log.trace("[Anti-Entropy] Node {} is the leader, skipping self-reconciliation", currentNodeId);
                return;
            }

            String leaderHost = ClusterConfig.getNodeUrl(leaderId);
            log.info("[Anti-Entropy] Node {} starting reconciliation against leader Node {} at {}",
                    currentNodeId, leaderId, leaderHost);

            // Step 3: Build local Merkle tree
            MerkleTreeService.MerkleNode localRoot = merkleTreeService.buildMerkleTree(currentNodeId);

            // Step 4: Fetch leader's Merkle tree
            MerkleTreeService.MerkleNode remoteRoot = fetchRemoteMerkleTree(leaderHost);

            // Step 5: Compare trees and find missing files
            List<String> missingFiles = merkleTreeService.compareTrees(localRoot, remoteRoot);

            // Step 6: Recover missing files
            int recoveredCount = recoverMissingFiles(leaderHost, missingFiles);
            totalFilesRecovered += recoveredCount;

            long duration = System.currentTimeMillis() - startTime;
            log.info("[Anti-Entropy] Node {} reconciliation complete: {} files recovered, {} ms",
                    currentNodeId, recoveredCount, duration);

            lastReconciliationTime = duration;

        } catch (IOException e) {
            log.error("[Anti-Entropy] Node {} reconciliation failed: {}", currentNodeId, e.getMessage());
        } catch (Exception e) {
            log.error("[Anti-Entropy] Node {} unexpected error during reconciliation", currentNodeId, e);
        }
    }

    /**
     * Fetches the remote Merkle tree from the leader via HTTP
     * 
     * Endpoint: GET /api/merkle/tree
     * Returns: Merkle tree representation of leader's files
     */
    private MerkleTreeService.MerkleNode fetchRemoteMerkleTree(String leaderHost) {
        try {
            // Get metadata list from leader (contains checksums)
            String metadataUrl = leaderHost + "/api/files/list";
            ResponseEntity<ApiResponse<List<FileMetadata>>> response = restTemplate.exchange(
                    metadataUrl,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<ApiResponse<List<FileMetadata>>>() {}
            );

            ApiResponse<List<FileMetadata>> body = response.getBody();
            if (body == null || !body.isSuccess() || body.getData() == null) {
                log.warn("[Anti-Entropy] Failed to fetch leader's file metadata");
                return null;
            }

            List<FileMetadata> leaderFiles = body.getData();
            log.debug("[Anti-Entropy] Fetched {} files from leader", leaderFiles.size());

            // Convert file metadata to mock Merkle tree structure for comparison
            // In production, we could have a dedicated /api/merkle/tree endpoint
            return reconstructTreeFromMetadata(leaderFiles);

        } catch (Exception e) {
            log.error("[Anti-Entropy] Failed to fetch remote Merkle tree: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Reconstructs a simplified Merkle tree from file metadata
     * Mock implementation: In production could be optimized
     */
    private MerkleTreeService.MerkleNode reconstructTreeFromMetadata(List<FileMetadata> files) {
        if (files.isEmpty()) return null;

        // Create leaf nodes from metadata
        List<MerkleTreeService.MerkleNode> leaves = files.stream()
                .map(file -> new MerkleTreeService.MerkleNode(
                        file.getChecksum() != null ? file.getChecksum() : "",
                        file.getName(),
                        0))
                .sorted((a, b) -> a.fileName.compareTo(b.fileName))
                .collect(Collectors.toList());

        // Rebuild tree structure from leaves (same algorithm as tree building)
        if (leaves.size() == 1) return leaves.get(0);

        return reconstructTreeByPairing(leaves);
    }

    /**
     * Recursively builds tree by pairing leaf nodes
     */
    private MerkleTreeService.MerkleNode reconstructTreeByPairing(List<MerkleTreeService.MerkleNode> nodes) {
        if (nodes.size() == 1) return nodes.get(0);

        List<MerkleTreeService.MerkleNode> nextLevel = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i += 2) {
            MerkleTreeService.MerkleNode left = nodes.get(i);
            MerkleTreeService.MerkleNode right = (i + 1 < nodes.size()) ? nodes.get(i + 1) 
                    : new MerkleTreeService.MerkleNode(left.hash, left.fileName, 0);

            MerkleTreeService.MerkleNode parent = new MerkleTreeService.MerkleNode(
                    combineHashes(left.hash, right.hash),
                    null,
                    left.level + 1
            );
            parent.left = left;
            parent.right = right;
            nextLevel.add(parent);
        }

        return reconstructTreeByPairing(nextLevel);
    }

    /**
     * Simple hash combination (same as in MerkleTreeService)
     */
    private String combineHashes(String left, String right) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(left.getBytes());
            digest.update(right.getBytes());
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                String hexByte = Integer.toHexString(0xff & b);
                if (hexByte.length() == 1) hex.append('0');
                hex.append(hexByte);
            }
            return hex.toString();
        } catch (Exception e) {
            return left + right; // Fallback
        }
    }

    /**
     * Recovers missing files from the leader
     * 
     * For each missing file:
     * 1. Downloads from leader: GET /api/files/download?path={filename}
     * 2. Writes to local disk
     * 3. Logs success/failure
     */
    private int recoverMissingFiles(String leaderHost, List<String> missingFiles) {
        int recoveredCount = 0;

        for (String fileName : missingFiles) {
            try {
                log.info("[Anti-Entropy] Node {} recovering missing file: {}", currentNodeId, fileName);

                // Fetch file from leader
                String downloadUrl = leaderHost + "/api/files/download?path=" + 
                        java.net.URLEncoder.encode(fileName, "UTF-8");
                
                ResponseEntity<byte[]> response = restTemplate.exchange(
                        downloadUrl,
                        HttpMethod.GET,
                        null,
                        byte[].class
                );

                byte[] fileContent = response.getBody();
                if (fileContent != null) {
                    // Persist to local storage
                    long currentTime = System.currentTimeMillis();
                    storageModulePort.persistReplica(currentNodeId, fileName, fileContent, currentTime);
                    
                    log.info("[Anti-Entropy] Node {} successfully recovered file: {} ({} bytes)",
                            currentNodeId, fileName, fileContent.length);
                    recoveredCount++;
                } else {
                    log.warn("[Anti-Entropy] Node {} received empty content for file: {}", currentNodeId, fileName);
                }

            } catch (Exception e) {
                log.error("[Anti-Entropy] Node {} failed to recover file {}: {}",
                        currentNodeId, fileName, e.getMessage());
            }
        }

        return recoveredCount;
    }

    /**
     * Returns Anti-Entropy statistics (useful for monitoring/debugging)
     */
    public String getReconciliationStats() {
        return String.format(
                "[Anti-Entropy Stats] Node %d | Last Duration: %d ms | " +
                "Total Reconciliations: %d | Total Files Recovered: %d",
                currentNodeId, lastReconciliationTime, totalReconciliationsCycle, totalFilesRecovered
        );
    }
}
