package com.mtbs.tenant.attachment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mtbs.tenant.attachment.enums.AttachmentPurpose;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AttachmentResponse {

    private Long id;
    private AttachmentPurpose purpose;
    private String fileName;
    private String contentType;
    private Long sizeBytes;

    /** Computed, not persisted — see Attachment entity Javadoc. */
    private String url;

    private Instant createdAt;
}
