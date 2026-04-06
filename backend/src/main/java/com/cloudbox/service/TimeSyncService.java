package com.cloudbox.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.cloudbox.config.ClusterConfig;
import com.cloudbox.model.ClockInfo;
import com.cloudbox.model.SkewReport;

import jakarta.annotation.PostConstruct;

/**
 * TimeSyncService â€” Berkeley Algorithm Time Synchronization
 *
 * Implements:
 *   1. Berkeley Algorithm â€” Active push-based distributed time synchronization:
 *      - The node with the lowest alive ID becomes the master each round.
 *      - Master polls all alive slaves via GET /api/timesync/time (RTT-compensated).
 *      - Master computes the cluster average: avgTime = mean(allCollectedTimes).
 *      - Master pushes individual correction deltas to every node (including itself):
 *            Î´áµ¢ = avgTime âˆ’ nodeTime_i
 *      - Each slave shifts its accumulated correction offset by Î´áµ¢.
 *      - Rounds run every 10 s. Self-elected via lowest-alive-ID rule.
 *
 *   2. Hybrid Logical Clock (HLC) â€” combines physical time with a logical
 *      counter to guarantee causal ordering even when physical clocks drift.
 *
 *   3. Lamport Timestamps â€” strictly increasing logical counter used for
 *      last-write-wins conflict resolution in ReplicationService.
 */
@Service
public class TimeSyncService {

    private final RestTemplate restTemplate;
    private final NodeRegistry nodeRegistry;
    private final ConsensusService consensusService;
    private final int  nodeId;
    private final long skewThresholdMs;

    /* â”€â”€ Hybrid Logical Clock (HLC) â”€â”€ */
    private volatile long hlcPhysical;
    private volatile int  hlcCounter;

    /* â”€â”€ Lamport clock â”€â”€ */
    private final AtomicLong lamport = new AtomicLong(0);

    /* â”€â”€ Berkeley state â”€â”€ */
    private volatile long    berkeleyCorrectionMs = 0;   // accumulated correction applied to this node
    private volatile long    lastRoundDeltaMs     = 0;   // delta from the most recent round
    private volatile long    lastBerkeleyRoundMs  = 0;   // epoch ms of last round / last correction received
    private volatile int     berkeleyMasterNodeId = -1;  // who ran the last round
    private volatile int     berkeleyRoundNumber  = 0;   // monotonic counter (master-maintained)
    private volatile boolean synced               = false;

    /* Master-side per-node data from the last round */
    private final Map<Integer, Long> lastDeltas       = new ConcurrentHashMap<>(); // nodeId â†’ correction delta
    private final Map<Integer, Long> peerMaxSkew      = new ConcurrentHashMap<>(); // nodeId â†’ peak |delta|
    private final Map<Integer, Long> peerLastMeasured = new ConcurrentHashMap<>(); // nodeId → epoch ms
    private volatile long            lastBerkeleyAvgTimeMs = 0; // cluster average time from last round
    public TimeSyncService(
            RestTemplate restTemplate,
            NodeRegistry nodeRegistry,
            @Lazy ConsensusService consensusService,
            @Value("${cloudbox.node-id:1}") int nodeId,
            @Value("${cloudbox.timesync.clock-skew-threshold-ms:100}") long skewThresholdMs) {
        this.restTemplate     = restTemplate;
        this.nodeRegistry     = nodeRegistry;
        this.consensusService = consensusService;
        this.nodeId           = nodeId;
        this.skewThresholdMs  = skewThresholdMs;
        this.hlcPhysical      = System.currentTimeMillis();
        this.hlcCounter       = 0;
    }

    @PostConstruct
    public void init() {
        // Restore Lamport from metrics.json (via NodeRegistry) so it never resets on restart
        lamport.accumulateAndGet(nodeRegistry.loadSavedLamport(), Math::max);
        // Restore accumulated Berkeley correction so correctedTimeMs() stays accurate after restart
        long saved = nodeRegistry.loadSavedBerkeleyCorrection();
        if (saved != 0) berkeleyCorrectionMs = saved;
        runBerkeleyRound();
    }

    /**
     * Berkeley synchronization round â€” scheduled every 10 s on every node.
     * Only the master (lowest alive node ID) actively collects and pushes.
     */
    @Scheduled(fixedDelayString = "${cloudbox.timesync.berkeley-interval:10000}")
    public void runBerkeleyRound() {
        List<Integer> aliveIds = nodeRegistry.getEffectiveAliveNodeIds();
        if (aliveIds.isEmpty()) return;

        // Berkeley master = minimum alive node ID (deterministic, all nodes agree without coordination).
        // Using consensus leader caused split-brain: when ZooKeeper is unavailable each node independently
        // runs Collections.shuffle() → different nodes pick different masters → no rounds run.
        int masterId = aliveIds.stream().min(Integer::compareTo).orElse(nodeId);
        berkeleyMasterNodeId = masterId;

        if (masterId != nodeId) {
            // Slave: corrections are pushed by the master; nothing to do proactively
            return;
        }

        // === This node is the master ===
        berkeleyRoundNumber++;

        // Step 1: Collect times from all alive nodes (RTT-compensated)
        long masterT0 = System.currentTimeMillis();
        Map<Integer, Long> gathered = new HashMap<>();
        gathered.put(nodeId, masterT0);

        for (int peerId : aliveIds) {
            if (peerId == nodeId) continue;
            try {
                long t0 = System.currentTimeMillis();
                Long peerTime = restTemplate.getForObject(
                        ClusterConfig.nodeUrl(peerId) + "/api/timesync/time", Long.class);
                if (peerTime == null) continue;
                long rtt = System.currentTimeMillis() - t0;
                gathered.put(peerId, peerTime + rtt / 2);
                peerLastMeasured.put(peerId, System.currentTimeMillis());
            } catch (Exception ignored) {}
        }

        if (gathered.size() < 2) {
            synced = false;
            return;
        }

        // Step 2: Compute cluster average
        long avgTime = (long) gathered.values().stream()
                .mapToLong(Long::longValue).average().orElse(masterT0);
        lastBerkeleyAvgTimeMs = avgTime;

        // Step 3: Compute and push correction deltas to every node
        for (Map.Entry<Integer, Long> entry : gathered.entrySet()) {
            int  peerId = entry.getKey();
            long delta  = avgTime - entry.getValue();   // positive â†’ node is behind average
            lastDeltas.put(peerId, delta);
            peerMaxSkew.merge(peerId, Math.abs(delta), Math::max);

            if (peerId == nodeId) {
                peerLastMeasured.put(nodeId, System.currentTimeMillis());
                applyCorrection(delta, 0);
            } else {
                try {
                    restTemplate.postForEntity(
                            ClusterConfig.nodeUrl(peerId) + "/api/timesync/correct?delta=" + delta
                                    + "&masterLamport=" + lamport.get(),
                            null, Void.class);
                } catch (Exception ignored) {}
            }
        }

        lastBerkeleyRoundMs = System.currentTimeMillis();
        // Do NOT increment Lamport here — Berkeley rounds are housekeeping, not causal events.
        synced = true;

        // Push round summary to all alive slaves so they have the full picture
        Map<String, Object> summary = new HashMap<>();
        summary.put("roundNumber",  berkeleyRoundNumber);
        summary.put("roundMs",      lastBerkeleyRoundMs);
        summary.put("masterLamport", lamport.get());
        summary.put("deltas",       new HashMap<>(lastDeltas));
        summary.put("maxSkew",      new HashMap<>(peerMaxSkew));
        summary.put("lastMeasured", new HashMap<>(peerLastMeasured));
        summary.put("avgTimeMs",    lastBerkeleyAvgTimeMs);
        // Broadcast to ALL nodes, including simulated-failed ones.
        // Simulated-failed nodes are still running and serving HTTP — if the frontend
        // lands on one, it needs fresh round data. Hard-killed nodes will simply throw
        // a connection-refused which is silently swallowed by the try-catch.
        for (int peerId = 1; peerId <= ClusterConfig.NODE_COUNT; peerId++) {
            if (peerId == nodeId) continue;
            try {
                restTemplate.postForEntity(
                        ClusterConfig.nodeUrl(peerId) + "/api/timesync/round-summary",
                        summary, Void.class);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Called by the REST endpoint when the master pushes a correction delta to this slave.
     * Also called by the master for its own self-correction (masterLamport = 0 in that case).
     */
    public void applyCorrection(long deltaMs, long masterLamport) {
        berkeleyCorrectionMs += deltaMs;
        lastRoundDeltaMs     = deltaMs;
        lastBerkeleyRoundMs  = System.currentTimeMillis();
        // Do NOT bump Lamport here — Berkeley sync is housekeeping, not a causal application event.
        // Lamport only advances on file ops and consensus messages.
        synced               = true;
        advanceHlc(System.currentTimeMillis());
    }

    /**
     * Called by the REST endpoint when the master pushes the full round summary to this slave.
     * Populates lastDeltas, peerMaxSkew, peerLastMeasured, and round counters so that
     * getSkewReport() returns the same meaningful data on any node, not just the master.
     */
    @SuppressWarnings("unchecked")
    public void applyRoundSummary(Map<String, Object> summary) {
        try {
            int rn = ((Number) summary.getOrDefault("roundNumber", 0)).intValue();
            if (rn < berkeleyRoundNumber) return; // stale — already have newer data
            berkeleyRoundNumber = rn;
            lastBerkeleyRoundMs = ((Number) summary.getOrDefault("roundMs", 0L)).longValue();
            lastBerkeleyAvgTimeMs = ((Number) summary.getOrDefault("avgTimeMs", 0L)).longValue();
            // Do NOT bump Lamport here — round summary is clock-sync housekeeping, not a causal event.
            Map<String, Object> deltas   = (Map<String, Object>) summary.getOrDefault("deltas",       Map.of());
            Map<String, Object> maxSkews = (Map<String, Object>) summary.getOrDefault("maxSkew",      Map.of());
            Map<String, Object> measured = (Map<String, Object>) summary.getOrDefault("lastMeasured", Map.of());
            deltas  .forEach((k, v) -> lastDeltas      .put(Integer.parseInt(k), ((Number) v).longValue()));
            maxSkews.forEach((k, v) -> peerMaxSkew     .merge(Integer.parseInt(k), ((Number) v).longValue(), Math::max));
            measured.forEach((k, v) -> peerLastMeasured.put(Integer.parseInt(k), ((Number) v).longValue()));
            synced = true;
        } catch (Exception ignored) {}
    }

    // â”€â”€ HLC operations â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** Advance HLC for a send event. Returns the new physical time component. */
    public synchronized long tickHlc() {
        long now = System.currentTimeMillis();
        if (now > hlcPhysical) {
            hlcPhysical = now;
            hlcCounter  = 0;
        } else {
            hlcCounter++;
        }
        return hlcPhysical;
    }

    /** Merge incoming HLC from a remote event (receive event). */
    public synchronized void mergeHlc(long remotePhysical, int remoteCounter) {
        long now         = System.currentTimeMillis();
        long newPhysical = Math.max(Math.max(now, remotePhysical), hlcPhysical);
        if (newPhysical == hlcPhysical && newPhysical == remotePhysical) {
            hlcCounter = Math.max(hlcCounter, remoteCounter) + 1;
        } else if (newPhysical == hlcPhysical) {
            hlcCounter++;
        } else if (newPhysical == remotePhysical) {
            hlcCounter = remoteCounter + 1;
        } else {
            hlcCounter = 0;
        }
        hlcPhysical = newPhysical;
    }

    private synchronized void advanceHlc(long physicalTime) {
        if (physicalTime > hlcPhysical) {
            hlcPhysical = physicalTime;
            hlcCounter  = 0;
        }
    }

    // â”€â”€ Lamport clock â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** Tick the Lamport clock for a send/local event. Returns new value. */
    public long tickLamport() {
        return lamport.incrementAndGet();
    }

    /** Update Lamport clock on receive: max(local, remote) + 1. */
    public void receiveLamport(long remoteTs) {
        lamport.accumulateAndGet(remoteTs, (local, remote) -> Math.max(local, remote) + 1);
    }

    /** Read current Lamport value (used by NodeRegistry for metrics persistence). */
    public long getLamport() { return lamport.get(); }

    /** Read accumulated Berkeley correction (used by NodeRegistry for metrics persistence). */
    public long getBerkeleyCorrection() { return berkeleyCorrectionMs; }

    // â”€â”€ Queries â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    public ClockInfo getClockInfo() {
        return new ClockInfo(
                nodeId,
                System.currentTimeMillis(),
                hlcPhysical,
                hlcCounter,
                lamport.get(),
                berkeleyCorrectionMs,
                lastRoundDeltaMs,
                lastBerkeleyRoundMs,
                berkeleyMasterNodeId,
                berkeleyRoundNumber,
                berkeleyMasterNodeId == nodeId,
                synced,
                skewThresholdMs);
    }

    /** Current epoch millis â€” used by peers for the Berkeley time-collection step. */
    public long currentTimeMs() {
        return System.currentTimeMillis();
    }
    /**
     * Berkeley-corrected wall-clock time.
     * Use this instead of System.currentTimeMillis() when stamping file events
     * so that all nodes agree on the ordering of writes across the cluster.
     */
    public long correctedTimeMs() {
        return System.currentTimeMillis() + berkeleyCorrectionMs;
    }
    public SkewReport getSkewReport() {
        boolean allSynced = true;
        List<SkewReport.NodeSkew> details = new ArrayList<>();
        List<Integer> aliveIds = nodeRegistry.getEffectiveAliveNodeIds();

        for (int peerId = 1; peerId <= ClusterConfig.NODE_COUNT; peerId++) {
            boolean peerAlive = aliveIds.contains(peerId);
            if (peerId == nodeId) {
                long   selfDelta = lastDeltas.getOrDefault(peerId, berkeleyCorrectionMs);
                long   selfMax   = peerMaxSkew.getOrDefault(peerId, Math.abs(selfDelta));
                boolean selfAlert = Math.abs(selfDelta) > skewThresholdMs;
                String selfStatus = peerAlive ? "HEALTHY" : "FAILED";
                long selfMeasured = peerLastMeasured.getOrDefault(peerId, System.currentTimeMillis());
                // nodeTimeMs = avgTime - delta = the clock reading captured during the last round
                long selfNodeTime = lastBerkeleyAvgTimeMs > 0 ? lastBerkeleyAvgTimeMs - selfDelta : 0;
                details.add(new SkewReport.NodeSkew(
                        peerId, selfNodeTime, selfMax, selfAlert, selfStatus,
                        selfMeasured, selfDelta));
                continue;
            }

            Long   delta    = lastDeltas.get(peerId);
            Long   maxSkew  = peerMaxSkew.getOrDefault(peerId, 0L);
            Long   measured = peerLastMeasured.get(peerId);
            boolean alert   = delta != null && Math.abs(delta) > skewThresholdMs;
            if (alert) allSynced = false;
            String  status  = !peerAlive ? "FAILED" : "HEALTHY";

            // nodeTimeMs = avgTime - delta (the node's raw clock reading from last round)
            long nodeTimeMs = (lastBerkeleyAvgTimeMs > 0 && delta != null)
                    ? lastBerkeleyAvgTimeMs - delta : 0;
            details.add(new SkewReport.NodeSkew(
                    peerId,
                    nodeTimeMs,
                    maxSkew,
                    alert,
                    status,
                    measured != null ? measured : 0,
                    delta   != null ? delta   : 0));  // correction d = avgTime - nodeTime
        }

        return new SkewReport(nodeId, skewThresholdMs, synced && allSynced, details);
    }
}
