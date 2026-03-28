package com.cloudbox.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.cloudbox.model.ApiResponse;
import com.cloudbox.model.FileMetadata;
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

    public FileController(QuorumWriteManager quorumWriteManager, StorageModulePort storageModulePort, ConsensusModulePort consensusModulePort) {
        this.quorumWriteManager = quorumWriteManager;
        this.storageModulePort = storageModulePort;
        this.consensusModulePort = consensusModulePort;
    }

    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<QuorumWriteResult>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "path", defaultValue = "/") String path) {
        try {
            String fileId = path.equals("/") ? file.getOriginalFilename() : path + file.getOriginalFilename();
            // Simplify fileId if it contains leading slash
            if (fileId.startsWith("/")) {
                fileId = fileId.substring(1);
            }
            log.info("Received request to upload file: {}", fileId);
            
            QuorumWriteResult result = quorumWriteManager.replicateWrite(fileId, file.getBytes());
            
            return ResponseEntity.ok(new ApiResponse<>(true, "File uploaded and replicated successfully", result));
        } catch (Exception e) {
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

            QuorumWriteResult result = quorumWriteManager.quorumDelete(fileId);
            return ResponseEntity.ok(new ApiResponse<>(true, "File deleted successfully", result));
        } catch (Exception e) {
            log.error("Failed to delete file", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse<>(false, "Failed to delete file: " + e.getMessage(), null));
        }
    }
}
