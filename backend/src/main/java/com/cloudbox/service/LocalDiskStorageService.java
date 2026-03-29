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
        File baseDirFile = new File(BASE_DIR);
        if (!baseDirFile.exists()) return allFiles;

        File[] nodeDirs = baseDirFile.listFiles();
        if (nodeDirs == null) return allFiles; // directory empty or I/O error

        List<String> distinctFileIds = new ArrayList<>();
        for (File nodeDir : nodeDirs) {
            if (nodeDir.isDirectory()) {
                File[] files = nodeDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (!distinctFileIds.contains(file.getName())) {
                            distinctFileIds.add(file.getName());
                            allFiles.add(FileMetadata.builder()
                                    .name(file.getName())
                                    .path("/" + file.getName())
                                    .size(file.length())
                                    .lastModified(file.lastModified())
                                    .type("file")
                                    .build());
                        }
                    }
                }
            }
        }
        return allFiles;
    }

    /**
     * Guard against path-traversal attacks (e.g. fileId = "../../etc/passwd").
     * Strips any directory component and rejects names that would escape the
     * node directory after normalisation.
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
