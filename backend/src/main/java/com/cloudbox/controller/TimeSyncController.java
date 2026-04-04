package com.cloudbox.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cloudbox.model.ApiResponse;
import com.cloudbox.model.ClockInfo;
import com.cloudbox.model.SkewReport;
import com.cloudbox.service.TimeSyncService;

/**
 * Time synchronization endpoints: clock state, NTP offset, per-node skew report.
 */
@RestController
@RequestMapping("/api/timesync")
public class TimeSyncController {

    private final TimeSyncService timeSyncService;

    public TimeSyncController(TimeSyncService timeSyncService) {
        this.timeSyncService = timeSyncService;
    }

    /** Returns comprehensive time synchronization status for this node. */
    @GetMapping("/status")
    public ApiResponse<ClockInfo> getStatus() {
        return ApiResponse.ok(timeSyncService.getClockInfo());
    }

    /** Returns the current epoch millis — used by peers for Cristian's algorithm. */
    @GetMapping("/time")
    public long getCurrentTime() {
        return timeSyncService.currentTimeMs();
    }
    /** Receives a correction delta pushed by the Berkeley master. */
    @PostMapping("/correct")
    public ResponseEntity<Void> applyCorrection(
            @RequestParam long delta,
            @RequestParam(defaultValue = "0") long masterLamport) {
        timeSyncService.applyCorrection(delta, masterLamport);
        return ResponseEntity.ok().build();
    }

    /** Receives the full round summary pushed by the master after each round. */
    @PostMapping("/round-summary")
    public ResponseEntity<Void> applyRoundSummary(@RequestBody Map<String, Object> summary) {
        timeSyncService.applyRoundSummary(summary);
        return ResponseEntity.ok().build();
    }
    /** Returns per-node clock skew measurements and alert status. */
    @GetMapping("/skew-report")
    public ApiResponse<SkewReport> getSkewReport() {
        return ApiResponse.ok(timeSyncService.getSkewReport());
    }
}
