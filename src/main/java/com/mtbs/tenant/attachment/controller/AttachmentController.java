package com.mtbs.tenant.attachment.controller;

import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.tenant.attachment.dto.AttachmentResponse;
import com.mtbs.tenant.attachment.enums.AttachmentPurpose;
import com.mtbs.tenant.attachment.service.AttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/${api.version}/attachments")
@RequiredArgsConstructor
@Tag(name = "Attachments", description = "Upload and manage shop-owned files (logo today; signature/stamp/QR-code reserved)")
@SecurityRequirement(name = "bearerAuth")
public class AttachmentController {

    private final AttachmentService attachmentService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('PERMISSION_TENANT_MANAGE')")
    @Operation(
        summary = "Upload an attachment",
        description = "Uploads a file (max 2MB, image/png|jpeg only) for the given purpose. " +
                      "WebP is rejected — it can't be embedded into the generated bill PDFs. " +
                      "Requires TENANT_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<AttachmentResponse>> upload(
            @RequestParam AttachmentPurpose purpose,
            @RequestPart("file") MultipartFile file) {

        AttachmentResponse response = attachmentService.upload(purpose, file);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Attachment uploaded successfully"));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERMISSION_TENANT_MANAGE')")
    @Operation(summary = "Get attachment metadata")
    public ResponseEntity<ApiResponse<AttachmentResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(attachmentService.getById(id), "Attachment fetched successfully"));
    }

    @GetMapping("/{id}/file")
    @Operation(summary = "Stream the raw file bytes")
    public ResponseEntity<byte[]> getFile(@PathVariable Long id) {
        byte[] bytes = attachmentService.getFileBytes(id);
        String contentType = attachmentService.getContentType(id);
        return ResponseEntity.ok()
                .header("Content-Type", contentType)
                .body(bytes);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERMISSION_TENANT_MANAGE')")
    @Operation(summary = "Delete an attachment")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        attachmentService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Attachment deleted successfully"));
    }
}
