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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Anti-Entropy Service for Data Reconciliation in Distributed File Storage
 *
 * Implements a gossip-based protocol that detects and repairs inconsistencies
 * between nodes. When a node misses replication during a failure, this service
 * automatically syncs data from the leader once the node recovers.
 *
 * Algorithm (simple set-difference):
 *   1. List local files in data/node-{self}/
 *   2. Fetch the leader's canonical file list via GET /api/files/list
 *   3. missing = leader files − local files  (by filename)
 *   4. Download each missing file from the leader
 *
 * Runs every 15 seconds so recovery is near-instant after a node comes back.
 *
 * Lecture References:
 * - Gossip Protocols (Lecture 4): information spreads across nodes
 * - Data Replication (Lecture 6): quorum-based approach + anti-entropy repair
 * - Eventual Consistency: over time, all nodes converge to the same state
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AntiEntropyService {

    private final MerkleTreeService merkleTreeService;
    private final StorageModulePort storageModulePort;
    private final ConsensusModulePort consensusModulePort;
    private final RestTemplate restTemplate = new RestTemplateBuilder().build();

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.cloudbox.controller.HealthController healthController;

    private static final String BASE_DIR = "data";

    @Value("${cloudbox.node-id}")
    private int currentNodeId;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    // Statistics
    private long lastReconciliationTime = 0;
    private int totalReconciliationsCycle = 0;
    private int totalFilesRecovered = 0;

    // ─── scheduled entry point ────────────────────────────────

    /**
     * Runs every 15 seconds. Compares local files against the leader's
     * canonical list and downloads anything that is missing.
     */
    @Scheduled(fixedRate = 15000, initialDelay = 10000)
    public void performAntiEntropyReconciliation() {
        // Skip entirely if this node is simulating failure
        if (healthController != null && healthController.isSimulatingFailure()) {
            return;
        }
        if (!isRunning.compareAndSet(false, true)) {
            return; // previous cycle still running
        }
        try {
            executeReconciliation();
        } finally {
            isRunning.set(false);
        }
    }

    // ─── core logic ───────────────────────────────────────────

    private void executeReconciliation() {
        long start = System.currentTimeMillis();
        totalReconciliationsCycle++;

        try {
            // 1. Who is the leader?
            int leaderId = consensusModulePort.getCurrentLeaderNodeId();
            if (leaderId < 1) {
                log.warn("[Anti-Entropy] Node {} — no leader, skipping", currentNodeId);
                return;
            }
            if (leaderId == currentNodeId) {
                // Leader doesn't need to sync with itself
                return;
            }

            String leaderUrl = ClusterConfig.getNodeUrl(leaderId);

            // 2. Build set of local file names
            Set<String> localFiles = listLocalFiles();

            // 3. Fetch the leader's canonical file list
            List<FileMetadata> leaderFiles = fetchLeaderFileList(leaderUrl);
            if (leaderFiles == null) {
                return; // leader unreachable — will retry next cycle
            }

            // 4. Compute missing = leader − local
            Set<String> leaderFileNames = leaderFiles.stream()
                    .map(FileMetadata::getName)
                    .collect(Collectors.toSet());

            List<String> missing = leaderFileNames.stream()
                    .filter(name -> !localFiles.contains(name))
                    .collect(Collectors.toList());

            // 4b. Compute stale = local − leader (deleted while this node was down)
            List<String> stale = localFiles.stream()
                    .filter(name -> !leaderFileNames.contains(name))
                    .collect(Collectors.toList());

            if (missing.isEmpty() && stale.isEmpty()) {
                log.trace("[Anti-Entropy] Node {} is in sync with leader", currentNodeId);
                return;
            }

            // 5. Download each missing file
            int recovered = 0;
            if (!missing.isEmpty()) {
                log.info("[Anti-Entropy] Node {} has {} missing file(s) — recovering from leader {}",
                        currentNodeId, missing.size(), leaderId);
                for (String fileName : missing) {
                    if (downloadAndStore(leaderUrl, fileName)) {
                        recovered++;
                    }
                }
                totalFilesRecovered += recovered;
            }

            // 6. Delete stale files that leader no longer has
            int deleted = 0;
            if (!stale.isEmpty()) {
                log.info("[Anti-Entropy] Node {} has {} stale file(s) — removing",
                        currentNodeId, stale.size());
                for (String fileName : stale) {
                    try {
                        storageModulePort.deleteReplica(currentNodeId, fileName);
                        deleted++;
                        log.info("[Anti-Entropy] Node {} deleted stale file: {}", currentNodeId, fileName);
                    } catch (Exception e) {
                        log.error("[Anti-Entropy] Node {} failed to delete stale file {}: {}",
                                currentNodeId, fileName, e.getMessage());
                    }
                }
            }

            long duration = System.currentTimeMillis() - start;
            lastReconciliationTime = duration;
            log.info("[Anti-Entropy] Node {} reconciliation done: {} recovered, {} deleted in {} ms",
                    currentNodeId, recovered, deleted, duration);

        } catch (Exception e) {
            log.error("[Anti-Entropy] Node {} reconciliation error: {}", currentNodeId, e.getMessage());
        }
    }

    // ─── helpers ──────────────────────────────────────────────

    /**
     * Lists file names currently stored in data/node-{self}/.
     */
    private Set<String> listLocalFiles() {
        Path nodeDir = Paths.get(BASE_DIR, "node-" + currentNodeId);
        if (!Files.exists(nodeDir)) {
            return Collections.emptySet();
        }
        try (Stream<Path> stream = Files.list(nodeDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            log.error("[Anti-Entropy] Failed to list local files: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * Fetches the leader's file list via GET /api/files/list.
     */
    private List<FileMetadata> fetchLeaderFileList(String leaderUrl) {
        try {
            ResponseEntity<ApiResponse<List<FileMetadata>>> resp = restTemplate.exchange(
                    leaderUrl + "/api/files/list",
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<ApiResponse<List<FileMetadata>>>() {}
            );
            ApiResponse<List<FileMetadata>> body = resp.getBody();
            if (body != null && body.isSuccess() && body.getData() != null) {
                return body.getData();
            }
        } catch (Exception e) {
            log.debug("[Anti-Entropy] Leader unreachable: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Downloads a single file from the leader and persists it locally.
     */
    private boolean downloadAndStore(String leaderUrl, String fileName) {
        try {
            String url = leaderUrl + "/api/files/download?path="
                    + java.net.URLEncoder.encode(fileName, "UTF-8");

            ResponseEntity<byte[]> resp = restTemplate.exchange(
                    url, HttpMethod.GET, null, byte[].class);

            byte[] content = resp.getBody();
            if (content == null || content.length == 0) {
                log.warn("[Anti-Entropy] Empty content for {}", fileName);
                return false;
            }

            storageModulePort.persistReplica(currentNodeId, fileName, content,
                    System.currentTimeMillis());

            log.info("[Anti-Entropy] Node {} recovered file: {} ({} bytes)",
                    currentNodeId, fileName, content.length);
            return true;

        } catch (Exception e) {
            log.error("[Anti-Entropy] Node {} failed to recover {}: {}",
                    currentNodeId, fileName, e.getMessage());
            return false;
        }
    }

    // ─── stats ────────────────────────────────────────────────

    public String getReconciliationStats() {
        return String.format(
                "[Anti-Entropy Stats] Node %d | Last Duration: %d ms | "
                        + "Total Reconciliations: %d | Total Files Recovered: %d",
                currentNodeId, lastReconciliationTime,
                totalReconciliationsCycle, totalFilesRecovered);
    }
}
