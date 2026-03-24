package com.cloudbox.service;

import org.springframework.stereotype.Service;

import com.cloudbox.model.HybridLogicalClock;

@Service
public class TimeSyncModuleAdapter implements TimeSyncPort {

    private final TimeSyncService timeSyncService;

    public TimeSyncModuleAdapter(TimeSyncService timeSyncService) {
        this.timeSyncService = timeSyncService;
    }

    @Override
    public long currentLogicalTimestamp() {
        if (timeSyncService.getCurrentLogicalTimestamp() != null) {
            return timeSyncService.getCurrentLogicalTimestamp().getTimestamp();
        }
        return System.currentTimeMillis(); // Fallback
    }
}