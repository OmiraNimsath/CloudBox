package com.cloudbox.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Externalized knobs for replication behavior.
 */
@ConfigurationProperties(prefix = "cloudbox.replication")
public class ReplicationProperties {

    @Min(1)
    private int quorumSize = ClusterConfig.QUORUM_SIZE;

    @NotNull
    private Duration writeTimeout = Duration.ofSeconds(30);

    @Min(0)
    private int retryCount = 3;

    @Min(1)
    private int replicationFactor = ClusterConfig.REPLICATION_FACTOR;

    public int getQuorumSize() {
        return quorumSize;
    }

    public void setQuorumSize(int quorumSize) {
        this.quorumSize = quorumSize;
    }

    public Duration getWriteTimeout() {
        return writeTimeout;
    }

    public void setWriteTimeout(Duration writeTimeout) {
        this.writeTimeout = writeTimeout;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int getReplicationFactor() {
        return replicationFactor;
    }

    public void setReplicationFactor(int replicationFactor) {
        this.replicationFactor = replicationFactor;
    }
}