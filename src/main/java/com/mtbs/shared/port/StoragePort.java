package com.mtbs.shared.port;

/**
 * Hexagonal port for binary file storage — mirrors EmailPort/NotificationPort.
 * V1 has one adapter (LocalDiskStorageAdapter); swapping to S3/Cloudinary later
 * means writing one new adapter class, zero changes to callers.
 */
public interface StoragePort {

    /**
     * Persists the given bytes and returns an opaque key the same adapter
     * can later use to retrieve or delete them. The key is internal —
     * callers never parse or construct it themselves.
     */
    String store(String originalFilename, String contentType, byte[] content);

    byte[] retrieve(String storageKey);

    void delete(String storageKey);
}
