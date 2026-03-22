package com.cloudbox.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Externalized configuration for time synchronization behavior.
 */
@ConfigurationProperties(prefix = "cloudbox.timesync")
public class TimeSyncProperties {

    /**
     * NTP synchronization interval.
     */
    @NotNull
    private Duration ntp_sync_interval = Duration.ofSeconds(60);

    /**
     * Maximum allowed clock skew threshold (milliseconds).
     * Alert if clock skew exceeds this value.
     */
    @Min(1)
    private long clock_skew_threshold_ms = 100;

    /**
     * Clock read timeout when fetching remote time.
     */
    @NotNull
    private Duration clock_read_timeout = Duration.ofSeconds(5);

    /**
     * Interval for skew detection checks.
     */
    @NotNull
    private Duration skew_check_interval = Duration.ofSeconds(30);

    /**
     * Enable NTP-based synchronization.
     * If false, falls back to system time.
     */
    private boolean enable_ntp = true;

    /**
     * NTP server address (if enable_ntp is true).
     */
    private String ntp_server = "pool.ntp.org";

    /**
     * Clock adjustment strategy: "gradual" or "instant".
     */
    private String clock_adjustment_strategy = "gradual";

    // Getters and Setters

    public Duration getNtp_sync_interval() {
        return ntp_sync_interval;
    }

    public void setNtp_sync_interval(Duration ntp_sync_interval) {
        this.ntp_sync_interval = ntp_sync_interval;
    }

    public long getClock_skew_threshold_ms() {
        return clock_skew_threshold_ms;
    }

    public void setClock_skew_threshold_ms(long clock_skew_threshold_ms) {
        this.clock_skew_threshold_ms = clock_skew_threshold_ms;
    }

    public Duration getClock_read_timeout() {
        return clock_read_timeout;
    }

    public void setClock_read_timeout(Duration clock_read_timeout) {
        this.clock_read_timeout = clock_read_timeout;
    }

    public Duration getSkew_check_interval() {
        return skew_check_interval;
    }

    public void setSkew_check_interval(Duration skew_check_interval) {
        this.skew_check_interval = skew_check_interval;
    }

    public boolean isEnable_ntp() {
        return enable_ntp;
    }

    public void setEnable_ntp(boolean enable_ntp) {
        this.enable_ntp = enable_ntp;
    }

    public String getNtp_server() {
        return ntp_server;
    }

    public void setNtp_server(String ntp_server) {
        this.ntp_server = ntp_server;
    }

    public String getClock_adjustment_strategy() {
        return clock_adjustment_strategy;
    }

    public void setClock_adjustment_strategy(String clock_adjustment_strategy) {
        this.clock_adjustment_strategy = clock_adjustment_strategy;
    }
}
