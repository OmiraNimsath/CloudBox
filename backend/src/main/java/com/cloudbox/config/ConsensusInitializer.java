package com.cloudbox.config;

import com.cloudbox.service.ClusterCoordinator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ConsensusInitializer starts the consensus & agreement module on application startup.
 *
 * Ensures all consensus components (leader election, partition detection, etc.)
 * are initialized after Spring Boot has finished starting up.
 */
@Slf4j
@Configuration
public class ConsensusInitializer {

    @Autowired
    private ClusterCoordinator clusterCoordinator;

    @Bean
    public ApplicationRunner startConsensus() {
        return args -> {
            try {
                log.info("Initializing consensus module on application startup");
                clusterCoordinator.startClusterCoordination();
                log.info("Consensus module initialized successfully");
            } catch (Exception e) {
                log.error("Failed to initialize consensus module", e);
                // Don't fail startup - log error but continue
                // In production, you may want to exit with error code
            }
        };
    }
}
