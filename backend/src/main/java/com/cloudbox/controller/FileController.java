package com.cloudbox.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.cloudbox.model.ApiResponse;
import com.cloudbox.model.FileMetadata;
import com.cloudbox.config.ReplicationProperties;
import com.cloudbox.service.ClockSynchronizer;
import com.cloudbox.service.QuorumWriteManager;
import com.cloudbox.service.QuorumWriteResult;
import com.cloudbox.service.StorageModulePort;
import com.cloudbox.service.ConsensusModulePort;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    private final QuorumWriteManager quorumWriteManager;
    private final StorageModulePort storageModulePort;
    private final ConsensusModulePort consensusModulePort;
    private final ReplicationProperties replicationProperties;
    private final ClockSynchronizer clockSynchronizer;

    public FileController(QuorumWriteManager quorumWriteManager, StorageModulePort storageModulePort, ConsensusModulePort consensusModulePort, ReplicationProperties replicationProperties, ClockSynchronizer clockSynchronizer) {
        this.quorumWriteManager = quorumWriteManager;
        this.storageModulePort = storageModulePort;
        this.consensusModulePort = consensusModulePort;
        this.replicationProperties = replicationProperties;
        this.clockSynchronizer = clockSynchronizer;
    }

    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<QuorumWriteResult>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "path", defaultValue = "/") String path) {
        try {
            String originalName = file.getOriginalFilename();
            if (originalName == null || originalName.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse<>(false, "File name is missing", null));
            }
            String fileId = path.equals("/") ? originalName : path + originalName;
            // Simplify fileId if it contains leading slash
            if (fileId.startsWith("/")) {
                fileId = fileId.substring(1);
            }
            log.info("Received request to upload file: {}", fileId);
            // Tick Lamport clock — upload is a causal send event
            clockSynchronizer.updateOnSend();
            QuorumWriteResult result = quorumWriteManager.replicateWrite(fileId, file.getBytes());
            
            return ResponseEntity.ok(new ApiResponse<>(true, "File uploaded and replicated successfully", result));
        } catch (IOException | RuntimeException e) {
            log.error("Failed to upload file", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse<>(false, "Failed to upload file: " + e.getMessage(), null));
        }
    }

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<FileMetadata>>> listFiles(@RequestParam(value = "path", defaultValue = "/") String path) {
        try {
            List<FileMetadata> files = storageModulePort.listFiles();
            // In a real app we would filter by path, returning all for simplicity
            return ResponseEntity.ok(new ApiResponse<>(true, "Files retrieved successfully", files));
        } catch (Exception e) {
            log.error("Failed to list files", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse<>(false, "Failed to list files: " + e.getMessage(), null));
        }
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> downloadFile(@RequestParam("path") String path) {
        try {
            String fileId = path.startsWith("/") ? path.substring(1) : path;
            
            // Retrieve from the leader or any available node in a real scenario
            // For simplicity, retrieving from the current leader node's storage
            int leaderNodeId = consensusModulePort.getCurrentLeaderNodeId();
            byte[] content = storageModulePort.retrieveReplica(leaderNodeId, fileId);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            // Extract just the filename for the download header
            String fileName = fileId.contains("/") ? fileId.substring(fileId.lastIndexOf('/') + 1) : fileId;
            headers.setContentDispositionFormData("attachment", fileName);

            // Tick Lamport clock — download is a causal receive event
            clockSynchronizer.updateOnSend();
            return new ResponseEntity<>(content, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("Failed to download file: {}", path, e);
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    @DeleteMapping("/delete")
    public ResponseEntity<ApiResponse<QuorumWriteResult>> deleteFile(@RequestParam("path") String path) {
        try {
            String fileId = path.startsWith("/") ? path.substring(1) : path;
            log.info("Received request to delete file: {}", fileId);
            // Tick Lamport clock — delete is a causal send event
            clockSynchronizer.updateOnSend();
            QuorumWriteResult result = quorumWriteManager.quorumDelete(fileId);
            return ResponseEntity.ok(new ApiResponse<>(true, "File deleted successfully", result));
        } catch (Exception e) {
            log.error("Failed to delete file", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse<>(false, "Failed to delete file: " + e.getMessage(), null));
        }
    }

    @GetMapping("/replication-status")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> getReplicationStatus() {
        try {
            List<FileMetadata> files = storageModulePort.listFiles();
            int rf = replicationProperties.getReplicationFactor();
            int quorum = replicationProperties.getQuorumSize();

            // Per-file replica counts: probe each node
            List<java.util.Map<String, Object>> fileStatuses = new ArrayList<>();
            for (FileMetadata meta : files) {
                int replicaCount = 0;
                List<Integer> presentOn = new ArrayList<>();
                for (int nodeId = 1; nodeId <= rf; nodeId++) {
                    try {
                        byte[] data = storageModulePort.retrieveReplica(nodeId, meta.getName());
                        if (data != null && data.length > 0) {
                            replicaCount++;
                            presentOn.add(nodeId);
                        }
                    } catch (Exception ignored) { }
                }
                fileStatuses.add(java.util.Map.of(
                    "fileName", meta.getName(),
                    "size", meta.getSize(),
                    "replicaCount", replicaCount,
                    "expectedReplicas", rf,
                    "presentOnNodes", presentOn,
                    "fullyReplicated", replicaCount >= rf
                ));
            }

            long fullyReplicated = fileStatuses.stream()
                .filter(f -> Boolean.TRUE.equals(f.get("fullyReplicated")))
                .count();

            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("replicationFactor", rf);
            result.put("quorumSize", quorum);
            result.put("totalFiles", files.size());
            result.put("fullyReplicatedFiles", fullyReplicated);
            result.put("underReplicatedFiles", files.size() - fullyReplicated);
            result.put("consistencyModel", "QUORUM_WRITE_LEADER_READ");
            result.put("files", fileStatuses);

            return ResponseEntity.ok(new ApiResponse<>(true, "Replication status retrieved", result));
        } catch (Exception e) {
            log.error("Failed to get replication status", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse<>(false, "Failed: " + e.getMessage(), null));
        }
    }
}
