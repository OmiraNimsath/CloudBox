package com.cloudbox.service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.cloudbox.config.ClusterConfig;
import com.cloudbox.model.FileMetadata;
import com.cloudbox.model.ReplicationStatus;

import jakarta.annotation.PostConstruct;

/**
 * ReplicationService — Data Replication & Consistency Module
 *
 * Strategy: Quorum-based replication (W + R > N, where N = 5, W = 3, R = 2).
 * Consistency model: QUORUM_CONSISTENCY
 *   - Writes: file is written locally, then replicated to all other nodes in
 *             parallel. Write is acknowledged once ≥ QUORUM_SIZE (3) nodes ACK.
 *   - Reads: served only after ≥ READ_QUORUM (2) nodes confirm they hold the file.
 *            A single-node response is rejected (503) to prevent serving stale
 *            or partially-written data.
 *   - Conflict resolution: last-write-wins using Lamport logical timestamps
 *             provided by TimeSyncService.
 *
 * Erasure coding vs full replication trade-off:
 *   Full replication (RF=5) was chosen to maximise read availability
 *   and demonstrate fault tolerance clearly. Every node holds every file.
 */
@Service
public class ReplicationService {

    private final RestTemplate restTemplate;
    private final NodeRegistry nodeRegistry;
    private final TimeSyncService timeSyncService;
    private final ConsensusService consensusService;
    private final Path storageDir;

    /* Lamport timestamp → version per file for conflict resolution */
    private final Map<String, Long> fileVersions = new ConcurrentHashMap<>();

    /* Berkeley-corrected upload timestamp per file — cluster-agreed wall-clock time */
    private final Map<String, Long> fileUploadTimes = new ConcurrentHashMap<>();

    /**
     * Delete tombstones: files deleted while a node was down.
     * Key = nodeId, Value = set of fileIds to delete when that node recovers.
     */
    private final Map<Integer, Set<String>> pendingDeletes = new ConcurrentHashMap<>();

    public ReplicationService(RestTemplate restTemplate,
                               NodeRegistry nodeRegistry,
                               TimeSyncService timeSyncService,
                               ConsensusService consensusService,
                               @Value("${cloudbox.storage.base-dir:./data/node-1}") String storageBasePath) {
        this.restTemplate = restTemplate;
        this.nodeRegistry = nodeRegistry;
        this.timeSyncService = timeSyncService;
        this.consensusService = consensusService;
        this.storageDir = Paths.get(storageBasePath);
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(storageDir);
    }

    // ── Write path ────────────────────────────────────────────────────────

    /**
     * Store a file locally and replicate it to all available peer nodes.
     * Returns the number of nodes that successfully acknowledged the write.
     * Throws if quorum (≥ 3 ACKs) cannot be reached.
     */
    public int replicateWrite(String fileId, byte[] content) {
        if (fileId == null || fileId.isBlank()) throw new IllegalArgumentException("fileId required");
        if (content == null) throw new IllegalArgumentException("content required");

        int aliveCount = nodeRegistry.getEffectiveAliveNodeIds().size();
        if (aliveCount < ClusterConfig.QUORUM_SIZE) {
            throw new IllegalStateException(
                "Write quorum unavailable — " + aliveCount + "/" + ClusterConfig.QUORUM_SIZE + " nodes alive");
        }

        long ts = timeSyncService.tickLamport();
        // Stamp upload with Berkeley-corrected time so all nodes agree on write ordering.
        long uploadedAt = timeSyncService.correctedTimeMs();

        /* Write to local storage first (counts as 1 ACK) */
        writeLocal(fileId, content);
        fileVersions.put(fileId, ts);
        fileUploadTimes.put(fileId, uploadedAt);
        consensusService.incrementZxid();
        int acks = 1;

        /* Fan-out replicate to peers */
        List<Integer> peers = new ArrayList<>(nodeRegistry.getEffectiveAliveNodeIds());
        peers.remove((Integer) nodeRegistry.selfNodeId());

        for (int peerId : peers) {
            if (replicateToPeer(peerId, fileId, content, ts, uploadedAt)) {
                acks++;
            }
        }

        if (acks < ClusterConfig.QUORUM_SIZE) {
            throw new IllegalStateException(
                    "Quorum not reached for fileId=" + fileId + " (acks=" + acks + ")");
        }
        return acks;
    }

    private boolean replicateToPeer(int peerId, String fileId, byte[] content, long ts, long uploadedAt) {
        try {
            String url = ClusterConfig.nodeUrl(peerId) + "/api/internal/replicate"
                    + "?fileId=" + fileId + "&timestamp=" + ts + "&uploadedAt=" + uploadedAt;
            restTemplate.postForEntity(url, content, String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Receives a replicated write from another node.
     * Last-write-wins conflict resolution: only applies if the incoming
     * logical timestamp is newer than the local version.
     */
    public void acceptReplica(String fileId, byte[] content, long logicalTimestamp, long uploadedAt) {
        long current = fileVersions.getOrDefault(fileId, -1L);
        if (logicalTimestamp >= current) {
            writeLocal(fileId, content);
            fileVersions.put(fileId, logicalTimestamp);
            timeSyncService.receiveLamport(logicalTimestamp);
            if (uploadedAt > 0) fileUploadTimes.put(fileId, uploadedAt);
        }
    }

    // ── Delete path ───────────────────────────────────────────────────────

    public void replicateDelete(String fileId) {
        deleteLocal(fileId);
        fileVersions.remove(fileId);

        List<Integer> aliveIds = nodeRegistry.getEffectiveAliveNodeIds();

        for (int nodeId = 1; nodeId <= ClusterConfig.NODE_COUNT; nodeId++) {
            if (nodeId == nodeRegistry.selfNodeId()) continue;

            if (!aliveIds.contains(nodeId)) {
                // Node is down — record tombstone so delete is applied on recovery
                pendingDeletes.computeIfAbsent(nodeId, k -> ConcurrentHashMap.newKeySet()).add(fileId);
                continue;
            }

            try {
                restTemplate.delete(ClusterConfig.nodeUrl(nodeId)
                        + "/api/internal/replicate?fileId=" + fileId);
                // Success — clear any stale tombstone for this file on this node
                Set<String> pending = pendingDeletes.get(nodeId);
                if (pending != null) pending.remove(fileId);
            } catch (Exception e) {
                // Treat transient failure as down — queue the tombstone
                pendingDeletes.computeIfAbsent(nodeId, k -> ConcurrentHashMap.newKeySet()).add(fileId);
            }
        }
    }

    // ── Read path ─────────────────────────────────────────────────────────

    public byte[] readFile(String fileId) throws IOException {
        Path p = storageDir.resolve(fileId);
        if (!Files.exists(p)) {
            throw new NoSuchFileException(fileId);
        }

        // Read quorum: local node = 1 confirmation; probe peers until READ_QUORUM reached
        int confirmations = 1;
        List<Integer> aliveIds = nodeRegistry.getEffectiveAliveNodeIds();
        for (int peerId = 1; peerId <= ClusterConfig.NODE_COUNT && confirmations < ClusterConfig.READ_QUORUM; peerId++) {
            if (peerId == nodeRegistry.selfNodeId()) continue;
            if (!aliveIds.contains(peerId)) continue;
            try {
                restTemplate.headForHeaders(ClusterConfig.nodeUrl(peerId)
                        + "/api/internal/replicate?fileId=" + fileId);
                confirmations++;
            } catch (Exception ignored) {}
        }

        if (confirmations < ClusterConfig.READ_QUORUM) {
            throw new IllegalStateException(
                    "Read quorum not reached for \"" + fileId + "\""
                    + " — only " + confirmations + " of " + ClusterConfig.READ_QUORUM + " required nodes available");
        }

        return Files.readAllBytes(p);
    }

    public List<FileMetadata> listFiles() {
        List<FileMetadata> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(storageDir)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) {
                    String name = p.getFileName().toString();
                    if (name.equals("metrics.json")) continue;
                    long size = Files.size(p);
                    // Use Berkeley-corrected upload time if available; fall back to OS mtime.
                    long uploaded = fileUploadTimes.getOrDefault(name, Files.getLastModifiedTime(p).toMillis());
                    long ts = fileVersions.getOrDefault(name, 0L);
                    List<Integer> nodes = presentOnNodes(name);
                    int rc = nodes.size();
                    result.add(new FileMetadata(name, "/" + name, size, uploaded, ts,
                            nodes, rc, ClusterConfig.REPLICATION_FACTOR,
                            rc >= ClusterConfig.REPLICATION_FACTOR));
                }
            }
        } catch (IOException e) {
            /* Return empty list on IO error */
        }
        return result;
    }

    /** Probe which peer nodes have this file (used for replica distribution display). */
    private List<Integer> presentOnNodes(String fileId) {
        List<Integer> result = new ArrayList<>();
        List<Integer> aliveIds = nodeRegistry.getEffectiveAliveNodeIds();
        for (int nodeId = 1; nodeId <= ClusterConfig.NODE_COUNT; nodeId++) {
            if (nodeId == nodeRegistry.selfNodeId()) {
                if (aliveIds.contains(nodeId) && Files.exists(storageDir.resolve(fileId))) result.add(nodeId);
                continue;
            }
            if (!aliveIds.contains(nodeId)) continue;
            try {
                restTemplate.headForHeaders(ClusterConfig.nodeUrl(nodeId)
                        + "/api/internal/replicate?fileId=" + fileId);
                result.add(nodeId);
            } catch (Exception ignored) {}
        }
        return result;
    }

    public int aliveNodeCount() {
        return nodeRegistry.getEffectiveAliveNodeIds().size();
    }

    // ── Replication status ────────────────────────────────────────────────

    public ReplicationStatus getReplicationStatus() {
        List<FileMetadata> files = listFiles();
        long fully = files.stream().filter(FileMetadata::fullyReplicated).count();
        long under = files.size() - fully;
        return new ReplicationStatus(
                ClusterConfig.REPLICATION_FACTOR,
                ClusterConfig.QUORUM_SIZE,
                "QUORUM_CONSISTENCY",
                files.size(),
                (int) fully,
                (int) under,
                files);
    }

    public int underReplicatedCount() {
        return (int) listFiles().stream().filter(f -> !f.fullyReplicated()).count();
    }

    // ── Local I/O helpers ─────────────────────────────────────────────────

    private void writeLocal(String fileId, byte[] content) {
        try {
            Files.write(storageDir.resolve(fileId), content,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Local write failed for " + fileId, e);
        }
    }

    public void deleteLocal(String fileId) {
        try {
            Files.deleteIfExists(storageDir.resolve(fileId));
        } catch (IOException e) {
            throw new RuntimeException("Local delete failed for " + fileId, e);
        }
    }

    public boolean existsLocally(String fileId) {
        return Files.exists(storageDir.resolve(fileId));
    }

    // ── Self-healing replication ──────────────────────────────────────────

    /**
     * Periodic self-healing: for every file this node holds locally,
     * push it to any alive peer that does not yet have it.
     * Also applies pending delete tombstones to recovered nodes.
     * Runs every 5 s for fast post-recovery convergence.
     */
    @Scheduled(fixedDelay = 5_000, initialDelay = 10_000)
    public void healUnderReplicatedFiles() {
        List<Integer> alivePeers = new ArrayList<>(nodeRegistry.getEffectiveAliveNodeIds());
        alivePeers.remove((Integer) nodeRegistry.selfNodeId());
        if (nodeRegistry.getEffectiveAliveNodeIds().size() < ClusterConfig.QUORUM_SIZE) return;
        if (alivePeers.isEmpty()) return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(storageDir)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p)) continue;
                String fileId = p.getFileName().toString();
                if (fileId.equals("metrics.json")) continue;
                long ts = fileVersions.getOrDefault(fileId, 0L);

                for (int peerId : alivePeers) {
                    Set<String> pd = pendingDeletes.get(peerId);
                    if (pd != null && pd.contains(fileId)) continue;

                    try {
                        restTemplate.headForHeaders(ClusterConfig.nodeUrl(peerId)
                                + "/api/internal/replicate?fileId=" + fileId);
                    } catch (Exception missing) {
                        int confirmations = 1;
                        for (int otherId : alivePeers) {
                            if (otherId == peerId) continue;
                            try {
                                restTemplate.headForHeaders(ClusterConfig.nodeUrl(otherId)
                                        + "/api/internal/replicate?fileId=" + fileId);
                                confirmations++;
                            } catch (Exception ignored) {}
                        }
                        if (confirmations < ClusterConfig.QUORUM_SIZE) continue;
                        try {
                            byte[] content = Files.readAllBytes(p);
                            long uploadedAt = fileUploadTimes.getOrDefault(fileId, 0L);
                            replicateToPeer(peerId, fileId, content, ts, uploadedAt);
                        } catch (IOException ignored) {}
                    }
                }
            }
        } catch (IOException ignored) {}

        for (int peerId : alivePeers) {
            Set<String> toDelete = pendingDeletes.get(peerId);
            if (toDelete == null || toDelete.isEmpty()) continue;
            for (String fileId : new ArrayList<>(toDelete)) {
                try {
                    restTemplate.delete(ClusterConfig.nodeUrl(peerId)
                            + "/api/internal/replicate?fileId=" + fileId);
                    toDelete.remove(fileId);
                } catch (Exception ignored) {}
            }
        }
    }
}
