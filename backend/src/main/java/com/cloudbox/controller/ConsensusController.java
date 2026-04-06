package com.cloudbox.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cloudbox.model.ApiResponse;
import com.cloudbox.model.ConsensusStatus;
import com.cloudbox.service.ConsensusService;

/**
 * Consensus and leader-election endpoints.
 */
@RestController
@RequestMapping("/api/consensus")
public class ConsensusController {

    private final ConsensusService consensusService;

    public ConsensusController(ConsensusService consensusService) {
        this.consensusService = consensusService;
    }

    /** Returns current consensus state: leader, epoch, ZXID, partition, quorum. */
    @GetMapping("/status")
    public ApiResponse<ConsensusStatus> getStatus() {
        return ApiResponse.ok(consensusService.getStatus());
    }
}
