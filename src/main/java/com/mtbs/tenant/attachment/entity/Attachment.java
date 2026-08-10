package com.mtbs.tenant.attachment.entity;

import com.mtbs.shared.entity.AuditableEntity;
import com.mtbs.tenant.attachment.enums.AttachmentPurpose;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * An uploaded file (logo today; signature/stamp/QR-code images once those
 * features exist). Lives in the tenant schema — files belong to one shop.
 *
 * storageKey is opaque to everything except the StoragePort adapter that
 * wrote it — callers never construct or parse it. The publicly fetchable
 * URL is computed at response time (AttachmentMapper), not persisted, so
 * swapping storage backends never requires a data migration.
 */
@Entity
@Table(name = "attachments")
@SQLDelete(sql = "UPDATE attachments SET deleted = true, deleted_at = NOW() WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Attachment extends AuditableEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AttachmentPurpose purpose;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;
}
