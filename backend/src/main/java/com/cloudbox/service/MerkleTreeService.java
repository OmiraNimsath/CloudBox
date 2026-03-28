package com.cloudbox.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.cloudbox.model.FileMetadata;

import lombok.extern.slf4j.Slf4j;

/**
 * Merkle Tree Service for Anti-Entropy Data Reconciliation
 * 
 * Computes SHA-256 checksums of local files and builds a Merkle tree representation
 * of the file system state. Used for detecting missing/corrupted files in distributed
 * nodes during anti-entropy reconciliation.
 * 
 * Key Concepts:
 * - Merkle Tree: Hash-based tree structure enabling efficient verification of large datasets
 * - Tree Depth: log₂(number of files) - allows O(log n) comparison
 * - Leaf Nodes: Hash of individual files
 * - Internal Nodes: Hash of two child nodes combined
 */
@Slf4j
@Service
public class MerkleTreeService {

    private static final String BASE_DIR = "data";
    private static final String HASH_ALGORITHM = "SHA-256";

    @Value("${cloudbox.node-id}")
    private int currentNodeId;

    /**
     * Represents a node in the Merkle Tree
     */
    public static class MerkleNode {
        public String hash;
        public String fileName;      // Only for leaf nodes
        public MerkleNode left;
        public MerkleNode right;
        public int level;            // 0 = leaf, increases upward

        public MerkleNode(String hash, String fileName, int level) {
            this.hash = hash;
            this.fileName = fileName;
            this.level = level;
        }

        @Override
        public String toString() {
            if (fileName != null) {
                return String.format("[LEAF] %s -> %s", fileName, hash.substring(0, 8));
            }
            return String.format("[NODE@L%d] %s", level, hash.substring(0, 8));
        }
    }

    /**
     * Computes SHA-256 hash of file content
     */
    public String computeFileHash(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashBytes = digest.digest(content);
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Converts byte array to hexadecimal string
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * Builds a Merkle Tree from the local file system state
     * Returns the root node of the tree
     */
    public MerkleNode buildMerkleTree(int nodeId) throws IOException {
        Path nodeDir = Paths.get(BASE_DIR, "node-" + nodeId);
        
        if (!Files.exists(nodeDir)) {
            log.warn("Node directory does not exist: {}", nodeDir);
            return null;
        }

        // Collect all files and sort for consistent tree structure
        List<MerkleNode> leafNodes = new ArrayList<>();
        Files.list(nodeDir)
                .filter(Files::isRegularFile)
                .sorted()
                .forEach(filePath -> {
                    try {
                        byte[] fileContent = Files.readAllBytes(filePath);
                        String fileHash = computeFileHash(fileContent);
                        String fileName = filePath.getFileName().toString();
                        
                        MerkleNode leaf = new MerkleNode(fileHash, fileName, 0);
                        leafNodes.add(leaf);
                        
                        log.trace("File leaf node: {} -> {}", fileName, fileHash.substring(0, 8));
                    } catch (IOException e) {
                        log.error("Failed to read file: {}", filePath, e);
                    }
                });

        if (leafNodes.isEmpty()) {
            log.debug("Node {} has no files, returning empty tree", nodeId);
            return null;
        }

        // Build tree bottom-up (leaf to root)
        return buildTreeFromLeaves(leafNodes);
    }

    /**
     * Recursively builds Merkle Tree from leaf nodes
     * Pairs leaves and computes parent hashes until reaching root
     */
    private MerkleNode buildTreeFromLeaves(List<MerkleNode> nodes) {
        if (nodes.isEmpty()) return null;
        if (nodes.size() == 1) return nodes.get(0);

        List<MerkleNode> parentLevel = new ArrayList<>();
        
        // Pair up nodes and compute parent hashes
        for (int i = 0; i < nodes.size(); i += 2) {
            MerkleNode left = nodes.get(i);
            MerkleNode right = (i + 1 < nodes.size()) ? nodes.get(i + 1) : null;
            
            // If odd number of nodes, duplicate last leaf
            if (right == null) {
                right = new MerkleNode(left.hash, left.fileName, left.level);
            }

            // Compute parent hash
            String parentHash = combineHashes(left.hash, right.hash);
            MerkleNode parent = new MerkleNode(parentHash, null, left.level + 1);
            parent.left = left;
            parent.right = right;
            
            parentLevel.add(parent);
            log.trace("Created parent node at level {}: {} + {} -> {}",
                    parent.level,
                    left, right, parentHash.substring(0, 8));
        }

        return buildTreeFromLeaves(parentLevel);
    }

    /**
     * Combines two child hashes to create parent hash
     * Hash(parent) = SHA-256(Hash(left) || Hash(right))
     */
    private String combineHashes(String leftHash, String rightHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            digest.update(leftHash.getBytes());
            digest.update(rightHash.getBytes());
            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Compares two Merkle trees and returns list of files that differ
     * Uses tree traversal to efficiently identify mismatches
     */
    public List<String> compareTrees(MerkleNode localRoot, MerkleNode remoteRoot) {
        List<String> missingFiles = new ArrayList<>();
        
        if (localRoot == null && remoteRoot == null) {
            log.debug("Both nodes are empty, no differences");
            return missingFiles;
        }

        if (localRoot == null || remoteRoot == null || !localRoot.hash.equals(remoteRoot.hash)) {
            // Trees differ, traverse to find differences
            traverseAndFindDifferences(localRoot, remoteRoot, missingFiles);
        } else {
            log.debug("Merkle trees are identical, no reconciliation needed");
        }

        return missingFiles;
    }

    /**
     * Recursively traverses both trees to find files that exist in remote but not local
     */
    private void traverseAndFindDifferences(MerkleNode localNode, MerkleNode remoteNode, 
                                           List<String> missingFiles) {
        // Leaf node comparison
        if (localNode != null && remoteNode != null && localNode.fileName != null) {
            if (!localNode.hash.equals(remoteNode.hash)) {
                log.warn("File mismatch: {}", localNode.fileName);
                missingFiles.add(localNode.fileName);
            }
            return;
        }

        // If local node missing but remote exists
        if (localNode == null && remoteNode != null) {
            collectAllFiles(remoteNode, missingFiles);
            return;
        }

        // If local node exists but remote missing - nothing to do (we have data remote doesn't)
        if (localNode != null && remoteNode == null) {
            return;
        }

        // Internal node: recursively compare children
        if (localNode != null && remoteNode != null && !localNode.hash.equals(remoteNode.hash)) {
            traverseAndFindDifferences(localNode.left, remoteNode.left, missingFiles);
            traverseAndFindDifferences(localNode.right, remoteNode.right, missingFiles);
        }
    }

    /**
     * Collects all files from a subtree (used when local has no data at all)
     */
    private void collectAllFiles(MerkleNode node, List<String> files) {
        if (node == null) return;
        
        if (node.fileName != null) {
            files.add(node.fileName);
            log.debug("Collecting missing file: {}", node.fileName);
        } else {
            collectAllFiles(node.left, files);
            collectAllFiles(node.right, files);
        }
    }

    /**
     * Prints Merkle Tree structure (for debugging)
     */
    public void printTree(MerkleNode root, String indent) {
        if (root == null) return;
        
        log.info("{}{}", indent, root);
        if (root.left != null || root.right != null) {
            if (root.left != null) {
                printTree(root.left, indent + "  L→ ");
            }
            if (root.right != null) {
                printTree(root.right, indent + "  R→ ");
            }
        }
    }

    /**
     * Validates file integrity by comparing computed hash with stored metadata hash
     */
    public boolean validateFileIntegrity(String filePath, FileMetadata metadata) {
        try {
            byte[] fileContent = Files.readAllBytes(Paths.get(filePath));
            String computedHash = computeFileHash(fileContent);
            
            boolean isValid = computedHash.equals(metadata.getChecksum());
            if (!isValid) {
                log.warn("File integrity check failed for {}: expected {}, got {}",
                        filePath, metadata.getChecksum(), computedHash);
            }
            return isValid;
        } catch (IOException e) {
            log.error("Failed to validate file integrity: {}", filePath, e);
            return false;
        }
    }
}
