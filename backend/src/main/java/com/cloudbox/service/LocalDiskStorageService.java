package com.cloudbox.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
        Path nodeDir = getNodeDir(nodeId);
        Path filePath = nodeDir.resolve(fileId);
        Files.write(filePath, content);
        // We could also store metadata alongside it (e.g. fileId.meta) to preserve timestamp, but keeping it simple for the implementation.
    }

    @Override
    public byte[] retrieveReplica(int nodeId, String fileId) throws Exception {
        Path nodeDir = getNodeDir(nodeId);
        Path filePath = nodeDir.resolve(fileId);
        
        if (Files.exists(filePath)) {
            return Files.readAllBytes(filePath);
        }
        throw new RuntimeException("File not found on node: " + nodeId);
    }

    @Override
    public void deleteReplica(int nodeId, String fileId) throws Exception {
        Path nodeDir = getNodeDir(nodeId);
        Path filePath = nodeDir.resolve(fileId);
        Files.deleteIfExists(filePath);
    }

    @Override
    public List<FileMetadata> listFiles() throws Exception {
        List<FileMetadata> allFiles = new ArrayList<>();
        File baseDirFile = new File(BASE_DIR);
        if (!baseDirFile.exists()) return allFiles;

        List<String> distinctFileIds = new ArrayList<>();
        for (File nodeDir : baseDirFile.listFiles()) {
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
}
