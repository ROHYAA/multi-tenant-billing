package com.mtbs.shared.adapter.storage;

import com.mtbs.shared.config.StorageProperties;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.shared.port.StoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * V1 storage backend — writes to a local directory, keyed by tenant schema
 * so files from different shops never collide on disk even though they
 * share one upload root. Swap for an S3/Cloudinary adapter later by
 * implementing StoragePort again; nothing else changes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LocalDiskStorageAdapter implements StoragePort {

    private final StorageProperties storageProperties;

    @Override
    public String store(String originalFilename, String contentType, byte[] content) {
        String schema = TenantContext.getSchemaName();
        String extension = extractExtension(originalFilename);
        String storageKey = schema + "/" + UUID.randomUUID() + extension;

        Path target = resolvePath(storageKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            log.error("Failed to write attachment to local disk: {}", storageKey, e);
            throw ResourceException.invalid("Failed to store file: " + e.getMessage());
        }

        return storageKey;
    }

    @Override
    public byte[] retrieve(String storageKey) {
        Path source = resolvePath(storageKey);
        try {
            return Files.readAllBytes(source);
        } catch (IOException e) {
            throw ResourceException.notFound("Attachment file", storageKey);
        }
    }

    @Override
    public void delete(String storageKey) {
        Path target = resolvePath(storageKey);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("Failed to delete attachment file: {} — {}", storageKey, e.getMessage());
        }
    }

    private Path resolvePath(String storageKey) {
        return Path.of(storageProperties.getLocalPath()).resolve(storageKey).normalize();
    }

    /**
     * Only alphanumeric extensions are kept — the result is concatenated
     * directly into a filesystem path, so anything else (path separators,
     * "..") from an attacker-controlled filename is stripped rather than
     * risking traversal outside the storage root.
     */
    private String extractExtension(String originalFilename) {
        if (!StringUtils.hasText(originalFilename) || !originalFilename.contains(".")) {
            return "";
        }
        String rawExtension = originalFilename.substring(originalFilename.lastIndexOf('.') + 1);
        String sanitized = rawExtension.replaceAll("[^a-zA-Z0-9]", "");
        return sanitized.isEmpty() ? "" : "." + sanitized;
    }
}
