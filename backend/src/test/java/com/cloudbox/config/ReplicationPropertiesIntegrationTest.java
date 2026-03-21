package com.cloudbox.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ReplicationPropertiesIntegrationTest {

    @Autowired
    private ReplicationProperties replicationProperties;

    @Test
    void shouldBindReplicationPropertiesFromConfiguration() {
        assertEquals(3, replicationProperties.getQuorumSize());
        assertEquals(Duration.ofSeconds(30), replicationProperties.getWriteTimeout());
        assertEquals(3, replicationProperties.getRetryCount());
        assertEquals(5, replicationProperties.getReplicationFactor());
    }
}