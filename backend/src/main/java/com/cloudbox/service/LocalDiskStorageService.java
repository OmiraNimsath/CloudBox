package com.cloudbox.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.cloudbox.model.FileMetadata;

@Service
public class LocalDiskStorageService implements StorageModulePort {

    private static final String BASE_DIR = "data";

    @org.springframework.beans.factory.annotation.Value("${cloudbox.node-id:1}")
    private int currentNodeId;

    public LocalDiskStorageService() {
        createBaseDirectories();
    }

    private void createBaseDirectories() {
        try {
            Files.createDirectories(Paths.get(BASE_DIR));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create base data directory", e);
        }
    }

    private Path getNodeDir(int nodeId) throws IOException {
        Path nodeDirPath = Paths.get(BASE_DIR, "node-" + nodeId);
        if (!Files.exists(nodeDirPath)) {
            Files.createDirectories(nodeDirPath);
        }
        return nodeDirPath;
    }

    @Override
    public void persistReplica(int nodeId, String fileId, byte[] content, long logicalTimestamp) throws Exception {
        Path nodeDir  = getNodeDir(nodeId);
        Path filePath = nodeDir.resolve(sanitizeFileId(fileId));
        Files.write(filePath, content);
    }

    @Override
    public byte[] retrieveReplica(int nodeId, String fileId) throws Exception {
        Path nodeDir  = getNodeDir(nodeId);
        Path filePath = nodeDir.resolve(sanitizeFileId(fileId));

        if (Files.exists(filePath)) {
            return Files.readAllBytes(filePath);
        }
        throw new RuntimeException("File not found on node: " + nodeId);
    }

    @Override
    public void deleteReplica(int nodeId, String fileId) throws Exception {
        Path nodeDir  = getNodeDir(nodeId);
        Path filePath = nodeDir.resolve(sanitizeFileId(fileId));
        Files.deleteIfExists(filePath);
    }

    @Override
    public List<FileMetadata> listFiles() throws Exception {
        List<FileMetadata> allFiles = new ArrayList<>();
        // Only list files from THIS node's directory (not all nodes on disk)
        File nodeDir = new File(BASE_DIR, "node-" + currentNodeId);
        if (!nodeDir.exists() || !nodeDir.isDirectory()) return allFiles;

        File[] files = nodeDir.listFiles();
        if (files == null) return allFiles;

        for (File file : files) {
            if (file.isFile()) {
                allFiles.add(FileMetadata.builder()
                        .name(file.getName())
                        .path("/" + file.getName())
                        .size(file.length())
                        .lastModified(file.lastModified())
                        .type("file")
                        .build());
            }
        }
        return allFiles;
    }

    /**
     * Guard against path-traversal attacks (e.g. fileId = "../../etc/passwd").
     * Keeps only the final filename segment; rejects names containing '..' or separators.
     */
    private String sanitizeFileId(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId must not be blank");
        }
        // Keep only the last path segment — discard any directory prefix
        String name = Paths.get(fileId).getFileName().toString();
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("Invalid fileId: " + fileId);
        }
        return name;
    }
}
