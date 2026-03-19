package com.cloudbox.config;

import com.cloudbox.service.FaultToleranceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

/**
 * Initializes the Fault Tolerance system on Spring Boot startup.
 * Automatically starts heartbeat monitoring, failure detection, and recovery management.
 * Also provides graceful shutdown on application termination.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FaultToleranceInitializer implements ApplicationRunner {
    
    private final FaultToleranceManager faultToleranceManager;
    
    @Override
    public void run(org.springframework.boot.ApplicationArguments args) throws Exception {
        try {
            faultToleranceManager.initialize();
            faultToleranceManager.enable();
            log.info("Fault Tolerance system initialized and enabled on startup");
        } catch (Exception e) {
            log.error("Failed to initialize Fault Tolerance system", e);
            throw e;
        }
    }
    
    @PreDestroy
    public void shutdown() {
        faultToleranceManager.shutdown();
        log.info("Fault Tolerance system shutdown gracefully");
    }
}

