package com.cloudbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Metadata for a stored file.
 *
 * Shared across all modules — replication, fault-tolerance, storage, etc.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileMetadata {

    /** Full logical path, e.g. "/docs/report.pdf". */
    private String path;

    /** File name only, e.g. "report.pdf". */
    private String name;

    /** Size in bytes. */
    private long size;

    /** MIME content type. */
    private String contentType;

    /** SHA-256 checksum of the file content. */
    private String checksum;

    /** Epoch millis when the file was uploaded / last modified. */
    private long lastModified;

    /** Entry type: "file" or "folder". */
    private String type;
}
