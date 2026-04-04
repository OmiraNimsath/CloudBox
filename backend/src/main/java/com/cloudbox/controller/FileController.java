package com.cloudbox.controller;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.cloudbox.config.ClusterConfig;
import com.cloudbox.model.ApiResponse;
import com.cloudbox.model.FileMetadata;
import com.cloudbox.service.NodeRegistry;
import com.cloudbox.service.ReplicationService;
import com.cloudbox.service.TimeSyncService;

/**
 * File upload, download, list, and delete operations.
 * All writes go through the quorum-based ReplicationService.
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final ReplicationService replicationService;
    private final TimeSyncService timeSyncService;
    private final NodeRegistry nodeRegistry;

    public FileController(ReplicationService replicationService,
                          TimeSyncService timeSyncService,
                          NodeRegistry nodeRegistry) {
        this.replicationService = replicationService;
        this.timeSyncService = timeSyncService;
        this.nodeRegistry = nodeRegistry;
    }

    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "path", defaultValue = "/") String path) {
        if (nodeRegistry.isSelfFailed()) return ResponseEntity.status(503).body(ApiResponse.error("Node unavailable"));
        try {
            String name = file.getOriginalFilename();
            if (name == null || name.isBlank()) {
                return ResponseEntity.badRequest().body(ApiResponse.error("File name missing"));
            }
            String fileId = path.equals("/") ? name : (path.replaceAll("^/", "") + name);
            timeSyncService.tickHlc();
            int acks = replicationService.replicateWrite(fileId, file.getBytes());
            return ResponseEntity.ok(ApiResponse.ok("Uploaded and replicated to " + acks + " nodes", null));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503).body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.error("Upload failed: " + e.getMessage()));
        }
    }

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<FileMetadata>>> list(
            @RequestParam(value = "path", defaultValue = "/") String path) {
        if (nodeRegistry.isSelfFailed()) return ResponseEntity.status(503).body(ApiResponse.error("Node unavailable"));
        int aliveCount = replicationService.aliveNodeCount();
        if (aliveCount < ClusterConfig.READ_QUORUM) {
            return ResponseEntity.status(503).body(
                ApiResponse.error("Read quorum unavailable — " + aliveCount + "/" + ClusterConfig.READ_QUORUM + " nodes alive"));
        }
        return ResponseEntity.ok(ApiResponse.ok(replicationService.listFiles()));
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@RequestParam("path") String path) {
        if (nodeRegistry.isSelfFailed()) return ResponseEntity.status(503).build();
        int aliveCount = replicationService.aliveNodeCount();
        if (aliveCount < ClusterConfig.READ_QUORUM) {
            return ResponseEntity.status(503).build();
        }
        try {
            String fileId = path.replaceAll("^/", "");
            timeSyncService.tickLamport();
            byte[] content = replicationService.readFile(fileId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileId + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(content);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503).build();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/delete")
    public ResponseEntity<ApiResponse<Void>> delete(@RequestParam("path") String path) {
        if (nodeRegistry.isSelfFailed()) return ResponseEntity.status(503).body(ApiResponse.error("Node unavailable"));
        try {
            String fileId = path.replaceAll("^/", "");
            timeSyncService.tickHlc();
            timeSyncService.tickLamport();
            replicationService.replicateDelete(fileId);
            return ResponseEntity.ok(ApiResponse.ok("Deleted", null));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/replication-status")
    public ResponseEntity<ApiResponse<Object>> replicationStatus() {
        return ResponseEntity.ok(ApiResponse.ok(replicationService.getReplicationStatus()));
    }
}
