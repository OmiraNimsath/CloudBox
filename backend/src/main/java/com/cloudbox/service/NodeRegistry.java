package com.cloudbox.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.cloudbox.config.ClusterConfig;
import com.cloudbox.model.NodeStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * NodeRegistry — Fault Tolerance Module
 *
 * Responsibilities:
 *   - Heartbeat monitoring: active HTTP pings every 3 s to detect failures
 *   - Failure detection: 3 consecutive missed beats → node marked UNHEALTHY
 *   - Quorum tracking: cluster has quorum when ≥ 3 nodes are alive
 *   - Recovery: when a node responds again its status is cleared automatically
 *   - MTTF / MTTR / Availability metrics updated in real time
 *
 * Design choice — replication redundancy:
 *   Each file is replicated to all 5 nodes (RF = 5). The system tolerates
 *   any 2 simultaneous failures while still maintaining quorum of 3.
 */
@Service
public class NodeRegistry {

    private static final int MISSED_BEATS_THRESHOLD = 3;
    private static final long HEARTBEAT_MS = 3_000;

    private final RestTemplate restTemplate;
    private final int selfNodeId;

    /* Per-node live state */
    private final Map<Integer, NodeStatus> statusMap = new ConcurrentHashMap<>();
    /* Consecutive missed heartbeat counters */
    private final Map<Integer, Integer> missedBeats = new ConcurrentHashMap<>();
    /* Epoch millis of last successful heartbeat per node */
    private final Map<Integer, Long> lastSeen = new ConcurrentHashMap<>();
    /* Epoch millis when a node first became UNHEALTHY (for MTTR) */
    private final Map<Integer, Long> failureStartMs = new ConcurrentHashMap<>();

    /* Admin-simulated failures: node ids that are being force-failed */
    private final Set<Integer> simulatedFailed = ConcurrentHashMap.newKeySet();

    /* Metrics accumulators */
    private volatile long systemStartMs;
    private volatile long quorumLossStartMs = -1;
    private volatile long lastQuorumRestoredMs;
    private final List<Double> mttrSamples = Collections.synchronizedList(new ArrayList<>());
    private final List<Double> mttfSamples = Collections.synchronizedList(new ArrayList<>());
    private final List<Double> quorumMttrSamples = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong quorumLossCount = new AtomicLong(0);

    private final Path metricsFile;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /* Lamport clock — persisted here so TimeSyncService can survive restarts */
    private volatile long savedLamport = 0;
    /* Berkeley accumulated correction — persisted so nodes resume with correct offset */
    private volatile long savedBerkeleyCorrection = 0;
    private final TimeSyncService timeSyncService;

    public NodeRegistry(RestTemplate restTemplate,
                        @Value("${cloudbox.node-id:1}") int selfNodeId,
                        @Value("${cloudbox.storage.base-dir:./data/node-1}") String storageBaseDir,
                        @Lazy TimeSyncService timeSyncService) {
        this.restTemplate    = restTemplate;
        this.selfNodeId      = selfNodeId;
        this.metricsFile     = Paths.get(storageBaseDir, "metrics.json");
        this.timeSyncService = timeSyncService;
    }

    @PostConstruct
    public void init() {
        systemStartMs = System.currentTimeMillis();
        lastQuorumRestoredMs = systemStartMs;
        loadMetrics();
        long now = System.currentTimeMillis();
        for (int i = 1; i <= ClusterConfig.NODE_COUNT; i++) {
            int nodeId = i;
            lastSeen.put(nodeId, now);
            missedBeats.put(nodeId, 0);
            statusMap.put(nodeId, buildStatus(nodeId, true, 0, null));
        }
    }

    @PreDestroy
    public void shutdown() {
        saveMetrics();
        statusMap.clear();
    }

    // ── Heartbeat monitoring ──────────────────────────────────────────────

    /**
     * Scheduled heartbeat probe: pings every node's /api/health endpoint.
     * Nodes that miss 3 consecutive beats are declared UNHEALTHY.
     * Nodes that respond again are automatically recovered.
     */
    @Scheduled(fixedDelay = HEARTBEAT_MS)
    public void runHeartbeatRound() {
        for (int nodeId = 1; nodeId <= ClusterConfig.NODE_COUNT; nodeId++) {
            if (simulatedFailed.contains(nodeId)) {
                int m = missedBeats.merge(nodeId, 1, Integer::sum);
                if (m >= MISSED_BEATS_THRESHOLD) {
                    failureStartMs.putIfAbsent(nodeId, System.currentTimeMillis());
                    updateStatus(nodeId, false, "Admin-simulated failure");
                } else {
                    updateStatus(nodeId, true, null);
                }
                continue;
            }

            // Self is always alive (no network ping needed) unless simulated above
            boolean alive = (nodeId == selfNodeId) || ping(nodeId);
            if (alive) {
                lastSeen.put(nodeId, System.currentTimeMillis());
                Integer prev = missedBeats.put(nodeId, 0);
                updateStatus(nodeId, true, null);
                /* Auto-recovery: if this node was unhealthy, record MTTR sample */
                if (prev != null && prev >= MISSED_BEATS_THRESHOLD) {
                    Long failStart = failureStartMs.remove(nodeId);
                    if (failStart != null) {
                        mttrSamples.add((System.currentTimeMillis() - failStart) / 1000.0);
                    }
                }
            } else {
                int missed = missedBeats.merge(nodeId, 1, Integer::sum);
                if (missed >= MISSED_BEATS_THRESHOLD) {
                    failureStartMs.putIfAbsent(nodeId, System.currentTimeMillis());
                    updateStatus(nodeId, false, "Missed " + missed + " heartbeats");
                } else {
                    updateStatus(nodeId, true, null);
                }
            }
        }
        trackQuorumLoss();
    }

    private boolean ping(int nodeId) {
        try {
            restTemplate.getForObject(ClusterConfig.nodeUrl(nodeId) + "/api/health", String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void updateStatus(int nodeId, boolean alive, String reason) {
        int missed = missedBeats.getOrDefault(nodeId, 0);
        String state = alive ? "HEALTHY" : "UNHEALTHY";
        statusMap.put(nodeId, buildStatus(nodeId, alive, missed, reason));
    }

    private NodeStatus buildStatus(int nodeId, boolean alive, int missed, String reason) {
        long hb = lastSeen.getOrDefault(nodeId, System.currentTimeMillis());
        return new NodeStatus(nodeId, alive ? "HEALTHY" : "UNHEALTHY",
                alive, false, hb, missed, reason);
    }

    // ── Admin simulation ──────────────────────────────────────────────────

    public void simulateFailure(int nodeId) {
        simulatedFailed.add(nodeId);
        missedBeats.put(nodeId, 0);
    }

    public void broadcastSimulatedFailure(int targetNodeId) {
        simulateFailure(targetNodeId);
        for (int peerId = 1; peerId <= ClusterConfig.NODE_COUNT; peerId++) {
            if (peerId == selfNodeId) continue;
            try {
                restTemplate.postForEntity(
                    ClusterConfig.nodeUrl(peerId) + "/api/internal/mark-failed?nodeId=" + targetNodeId,
                    null, Void.class);
            } catch (Exception ignored) {}
        }
    }

    public void simulateRecovery(int nodeId) {
        simulatedFailed.remove(nodeId);
        missedBeats.put(nodeId, 0);
        Long failStart = failureStartMs.remove(nodeId);
        if (failStart != null) {
            mttrSamples.add((System.currentTimeMillis() - failStart) / 1000.0);
        }
        lastSeen.put(nodeId, System.currentTimeMillis());
        updateStatus(nodeId, true, null);
    }

    public void broadcastSimulatedRecovery(int targetNodeId) {
        simulateRecovery(targetNodeId);
        for (int peerId = 1; peerId <= ClusterConfig.NODE_COUNT; peerId++) {
            if (peerId == selfNodeId) continue;
            try {
                restTemplate.postForEntity(
                    ClusterConfig.nodeUrl(peerId) + "/api/internal/mark-recovered?nodeId=" + targetNodeId,
                    null, Void.class);
            } catch (Exception ignored) {}
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────

    public Map<Integer, NodeStatus> getAllStatuses() {
        return Collections.unmodifiableMap(statusMap);
    }

    public NodeStatus getStatus(int nodeId) {
        return statusMap.get(nodeId);
    }

    public List<Integer> getAliveNodeIds() {
        List<Integer> alive = new ArrayList<>();
        statusMap.forEach((id, s) -> { if (s.alive()) alive.add(id); });
        return alive;
    }

    public List<Integer> getEffectiveAliveNodeIds() {
        List<Integer> alive = new ArrayList<>();
        statusMap.forEach((id, s) -> {
            if (s.alive() && !simulatedFailed.contains(id)) alive.add(id);
        });
        return alive;
    }

    public boolean hasQuorum() {
        return getAliveNodeIds().size() >= ClusterConfig.QUORUM_SIZE;
    }

    public int getLeaderId() {
        // Leader is determined by ConsensusService; node registry only tracks liveness.
        // Returns -1 when unknown; ConsensusService updates node statuses with leader flag.
        return statusMap.values().stream()
                .filter(NodeStatus::leader)
                .mapToInt(NodeStatus::nodeId)
                .findFirst().orElse(-1);
    }

    /** Called by ConsensusService to stamp the leader flag on a node's status record. */
    public void markLeader(int nodeId) {
        statusMap.forEach((id, s) -> statusMap.put(id,
                new NodeStatus(s.nodeId(), s.status(), s.alive(),
                        id == nodeId, s.lastHeartbeatMs(), s.missedHeartbeats(), s.failureReason())));
    }

    // ── Reliability metrics ───────────────────────────────────────────────

    private void trackQuorumLoss() {
        boolean q = hasQuorum();
        if (!q && quorumLossStartMs < 0) {
            double upDuration = (System.currentTimeMillis() - lastQuorumRestoredMs) / 1000.0;
            if (upDuration > 0) mttfSamples.add(upDuration);
            quorumLossStartMs = System.currentTimeMillis();
            quorumLossCount.incrementAndGet();
        } else if (q && quorumLossStartMs >= 0) {
            quorumMttrSamples.add((System.currentTimeMillis() - quorumLossStartMs) / 1000.0);
            quorumLossStartMs = -1;
            lastQuorumRestoredMs = System.currentTimeMillis();
        }
    }

    public double getMttfSeconds() {
        synchronized (mttfSamples) {
            if (quorumLossStartMs < 0) {
                double current = (System.currentTimeMillis() - lastQuorumRestoredMs) / 1000.0;
                double total = mttfSamples.stream().mapToDouble(d -> d).sum() + current;
                return total / (mttfSamples.size() + 1);
            }
            if (mttfSamples.isEmpty()) return 0.0;
            return mttfSamples.stream().mapToDouble(d -> d).average().orElse(0.0);
        }
    }

    public double getMttrSeconds() {
        synchronized (quorumMttrSamples) {
            if (quorumLossStartMs >= 0) {
                double current = (System.currentTimeMillis() - quorumLossStartMs) / 1000.0;
                double total = quorumMttrSamples.stream().mapToDouble(d -> d).sum() + current;
                return total / (quorumMttrSamples.size() + 1);
            }
            if (quorumMttrSamples.isEmpty()) return -1;
            return quorumMttrSamples.stream().mapToDouble(d -> d).average().orElse(-1);
        }
    }

    public double getAvailabilityPercent() {
        double mttf = getMttfSeconds();
        double mttr = getMttrSeconds();
        if (mttr < 0) return 100.0;
        if (mttf <= 0) return 0.0;
        return (mttf / (mttf + mttr)) * 100.0;
    }

    public int totalNodes() { return ClusterConfig.NODE_COUNT; }
    public int selfNodeId() { return selfNodeId; }

    public boolean isSelfFailed() { return simulatedFailed.contains(selfNodeId); }

    /** Returns the Lamport value loaded from disk at startup for TimeSyncService to seed itself. */
    public long loadSavedLamport() { return savedLamport; }

    /** Returns the accumulated Berkeley correction loaded from disk at startup. */
    public long loadSavedBerkeleyCorrection() { return savedBerkeleyCorrection; }

    // ── Metrics persistence & gossip ─────────────────────────────────────

    /** Build a snapshot map of current metric state. */
    public Map<String, Object> exportMetrics() {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("systemStartMs",           systemStartMs);
        m.put("lastQuorumRestoredMs",    lastQuorumRestoredMs);
        m.put("quorumLossCount",         quorumLossCount.get());
        m.put("mttfSamples",             new ArrayList<>(mttfSamples));
        m.put("mttrSamples",             new ArrayList<>(mttrSamples));
        m.put("quorumMttrSamples",       new ArrayList<>(quorumMttrSamples));
        m.put("lamportTimestamp",        timeSyncService.getLamport());
        m.put("berkeleyCorrectionMs",    timeSyncService.getBerkeleyCorrection());
        return m;
    }

    /**
     * Apply a metrics snapshot received from a peer.
     * Only replaces local data when the peer snapshot has more history
     * (more mttfSamples). On fresh startup (local empty) always applies.
     */
    @SuppressWarnings("unchecked")
    public void importMetrics(Map<String, Object> data) {
        try {
            List<Number> peerMttf = (List<Number>) data.getOrDefault("mttfSamples", List.of());
            if (peerMttf.size() <= mttfSamples.size()) return; // local already has more history
            // Apply the richer snapshot
            systemStartMs        = ((Number) data.getOrDefault("systemStartMs",        systemStartMs)).longValue();
            lastQuorumRestoredMs = ((Number) data.getOrDefault("lastQuorumRestoredMs", lastQuorumRestoredMs)).longValue();
            quorumLossCount.set(  ((Number) data.getOrDefault("quorumLossCount",       0)).longValue());
            mttfSamples.clear();
            mttrSamples.clear();
            quorumMttrSamples.clear();
            peerMttf.forEach(v -> mttfSamples.add(v.doubleValue()));
            ((List<Number>) data.getOrDefault("mttrSamples",       List.of())).forEach(v -> mttrSamples.add(v.doubleValue()));
            ((List<Number>) data.getOrDefault("quorumMttrSamples", List.of())).forEach(v -> quorumMttrSamples.add(v.doubleValue()));
            long peerLamport = ((Number) data.getOrDefault("lamportTimestamp", 0L)).longValue();
            if (peerLamport > savedLamport) savedLamport = peerLamport;
            // Do NOT call receiveLamport here — metrics gossip is not a causal application event.
            // Lamport only advances on file ops and consensus messages.
            savedBerkeleyCorrection = ((Number) data.getOrDefault("berkeleyCorrectionMs", 0L)).longValue();

        } catch (Exception ignored) {}
    }

    /** Save to local disk + broadcast to every peer so all nodes stay in sync. */
    @Scheduled(fixedDelay = 10_000)
    public void saveMetrics() {
        Map<String, Object> snapshot = exportMetrics();
        // Persist locally
        try {
            Files.createDirectories(metricsFile.getParent());
            objectMapper.writeValue(metricsFile.toFile(), snapshot);
        } catch (IOException ignored) {}
        // Gossip to all peers
        for (int peerId = 1; peerId <= ClusterConfig.NODE_COUNT; peerId++) {
            if (peerId == selfNodeId) continue;
            try {
                restTemplate.postForEntity(
                    ClusterConfig.nodeUrl(peerId) + "/api/internal/metrics-sync",
                    snapshot, Void.class);
            } catch (Exception ignored) {}
        }
    }

    /**
     * On startup: load local file first, then try every peer to find
     * a richer snapshot (peer has been running while this node was down).
     */
    @SuppressWarnings("unchecked")
    private void loadMetrics() {
        // 1. Load from local disk
        try {
            if (Files.exists(metricsFile)) {
                Map<String, Object> data = objectMapper.readValue(metricsFile.toFile(), Map.class);
                importMetrics(data);
                // importMetrics skips if peer has fewer samples — bootstrap from disk unconditionally
                systemStartMs        = ((Number) data.getOrDefault("systemStartMs",        systemStartMs)).longValue();
                lastQuorumRestoredMs = ((Number) data.getOrDefault("lastQuorumRestoredMs", lastQuorumRestoredMs)).longValue();
                quorumLossCount.set(  ((Number) data.getOrDefault("quorumLossCount",       0)).longValue());
                if (mttfSamples.isEmpty()) {
                    ((List<Number>) data.getOrDefault("mttfSamples",       List.of())).forEach(v -> mttfSamples.add(v.doubleValue()));
                    ((List<Number>) data.getOrDefault("mttrSamples",       List.of())).forEach(v -> mttrSamples.add(v.doubleValue()));
                    ((List<Number>) data.getOrDefault("quorumMttrSamples", List.of())).forEach(v -> quorumMttrSamples.add(v.doubleValue()));
                }
                long diskLamport = ((Number) data.getOrDefault("lamportTimestamp", 0L)).longValue();
                if (diskLamport > savedLamport) savedLamport = diskLamport;
                long diskCorrection = ((Number) data.getOrDefault("berkeleyCorrectionMs", 0L)).longValue();
                savedBerkeleyCorrection = diskCorrection;
            }
        } catch (Exception ignored) {}
        // 2. Pull from peers — take the first peer that has richer data
        for (int peerId = 1; peerId <= ClusterConfig.NODE_COUNT; peerId++) {
            if (peerId == selfNodeId) continue;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> peerData = restTemplate.getForObject(
                    ClusterConfig.nodeUrl(peerId) + "/api/internal/metrics-snapshot",
                    Map.class);
                if (peerData != null) importMetrics(peerData);
            } catch (Exception ignored) {}
        }
    }
}
