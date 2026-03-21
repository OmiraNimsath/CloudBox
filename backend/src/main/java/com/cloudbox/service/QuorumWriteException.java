package com.cloudbox.service;

/**
 * Raised when a write cannot achieve quorum within configured retries.
 */
public class QuorumWriteException extends RuntimeException {

    public QuorumWriteException(String message) {
        super(message);
    }
}
