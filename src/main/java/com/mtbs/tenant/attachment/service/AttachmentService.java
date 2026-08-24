package com.mtbs.tenant.attachment.service;

import com.mtbs.shared.config.ApiProperties;
import com.mtbs.shared.config.StorageProperties;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.shared.port.StoragePort;
import com.mtbs.tenant.attachment.dto.AttachmentResponse;
import com.mtbs.tenant.attachment.entity.Attachment;
import com.mtbs.tenant.attachment.enums.AttachmentPurpose;
import com.mtbs.tenant.attachment.repository.AttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Set;

/**
 * Uploads/serves/deletes files via StoragePort. Not itself aware of which
 * storage backend is active — that's LocalDiskStorageAdapter's job (or a
 * future S3 adapter).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttachmentService {

    /**
     * WebP is deliberately excluded — the JDK's ImageIO has no built-in WebP
     * decoder, and iText's ImageDataFactory doesn't support it either, so a
     * webp logo/signature would upload successfully here but then silently
     * fail to embed at PDF-render time (BillRenderSupport.loadImage fails
     * soft and just skips it, logging "Image format cannot be recognized").
     * Rejecting it here gives an immediate, clear error instead.
     */
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/png", "image/jpeg");

    private final AttachmentRepository attachmentRepository;
    private final StoragePort storagePort;
    private final StorageProperties storageProperties;
    private final ApiProperties apiProperties;

    @Transactional
    public AttachmentResponse upload(AttachmentPurpose purpose, MultipartFile file) {
        validate(file);

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw ResourceException.invalid("Could not read uploaded file: " + e.getMessage());
        }

        String storageKey = storagePort.store(file.getOriginalFilename(), file.getContentType(), content);

        Attachment attachment = Attachment.builder()
                .purpose(purpose)
                .fileName(file.getOriginalFilename())
                .contentType(file.getContentType())
                .sizeBytes(file.getSize())
                .storageKey(storageKey)
                .build();

        Attachment saved = attachmentRepository.save(attachment);
        log.info("Attachment uploaded — id={}, purpose={}, sizeBytes={}", saved.getId(), purpose, saved.getSizeBytes());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public AttachmentResponse getById(Long id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public byte[] getFileBytes(Long id) {
        Attachment attachment = findOrThrow(id);
        return storagePort.retrieve(attachment.getStorageKey());
    }

    @Transactional(readOnly = true)
    public String getContentType(Long id) {
        return findOrThrow(id).getContentType();
    }

    @Transactional
    public void delete(Long id) {
        Attachment attachment = findOrThrow(id);
        storagePort.delete(attachment.getStorageKey());
        attachmentRepository.delete(attachment);
        log.info("Attachment deleted — id={}", id);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ResourceException.invalid("File is required");
        }
        if (file.getSize() > storageProperties.getMaxFileSizeBytes()) {
            throw ResourceException.invalid(
                    "File exceeds maximum size of " + (storageProperties.getMaxFileSizeBytes() / 1024 / 1024) + "MB");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
            throw ResourceException.invalid(
                    "Unsupported content type: " + file.getContentType()
                            + ". Allowed: " + ALLOWED_CONTENT_TYPES);
        }
    }

    private Attachment findOrThrow(Long id) {
        return attachmentRepository.findById(id)
                .orElseThrow(() -> ResourceException.notFound("Attachment", id));
    }

    private AttachmentResponse toResponse(Attachment attachment) {
        return AttachmentResponse.builder()
                .id(attachment.getId())
                .purpose(attachment.getPurpose())
                .fileName(attachment.getFileName())
                .contentType(attachment.getContentType())
                .sizeBytes(attachment.getSizeBytes())
                .url("/api/" + apiProperties.getVersion() + "/attachments/" + attachment.getId() + "/file")
                .createdAt(attachment.getCreatedAt())
                .build();
    }
}
