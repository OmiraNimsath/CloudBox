package com.cloudbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a proposal for atomic broadcast in the ZAB protocol.
 * 
 * - proposalId: unique identifier for this proposal
 * - epoch: election epoch when proposal was made
 * - zxid: ZooKeeper transaction ID (epoch + counter)
 * - data: the actual data/operation to be broadcast
 * - proposerId: node ID of the proposer
 * - timestamp: when proposal was created
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsensusProposal {
    private String proposalId;
    private long epoch;
    private long zxid;
    private String data;
    private int proposerId;
    private long timestamp;

    /** PROPOSED → COMMITTED once quorum ACKs received, or ABORTED on failure. */
    @Builder.Default
    private String status = "PROPOSED";

    /** Number of follower ACKs collected so far (leader counts as 1). */
    @Builder.Default
    private int ackCount = 1;
}
