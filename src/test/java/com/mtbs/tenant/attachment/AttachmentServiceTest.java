package com.mtbs.tenant.attachment;

import com.mtbs.app.MultiTenantBillingSystemApplication;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.support.TestSchemaHelper;
import com.mtbs.tenant.attachment.dto.AttachmentResponse;
import com.mtbs.tenant.attachment.enums.AttachmentPurpose;
import com.mtbs.tenant.attachment.service.AttachmentService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = MultiTenantBillingSystemApplication.class)
@ActiveProfiles("test")
@DisplayName("AttachmentService Integration Tests")
class AttachmentServiceTest {

    @Autowired
    private AttachmentService attachmentService;

    @Autowired
    private TestSchemaHelper testSchemaHelper;

    private String currentSchema;

    @BeforeEach
    void setUp() {
        currentSchema = testSchemaHelper.createFreshSchema();
        TenantContext.setTenantId(1L);
        TenantContext.setCurrentSchema(currentSchema);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        testSchemaHelper.dropSchema(currentSchema);
    }

    @Nested
    @DisplayName("upload")
    class UploadTests {

        @Test
        @DisplayName("upload valid PNG stores and returns a fetchable URL")
        void upload_validPng_storesFile() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "logo.png", "image/png", "fake-png-bytes".getBytes());

            AttachmentResponse response = attachmentService.upload(AttachmentPurpose.LOGO, file);

            assertNotNull(response.getId());
            assertEquals(AttachmentPurpose.LOGO, response.getPurpose());
            assertEquals("logo.png", response.getFileName());
            assertEquals("image/png", response.getContentType());
            assertTrue(response.getUrl().contains("/attachments/" + response.getId() + "/file"));

            byte[] retrieved = attachmentService.getFileBytes(response.getId());
            assertArrayEquals("fake-png-bytes".getBytes(), retrieved);
        }

        @Test
        @DisplayName("upload unsupported content type throws")
        void upload_unsupportedContentType_throws() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "doc.pdf", "application/pdf", "not-an-image".getBytes());

            assertThrows(ResourceException.class, () ->
                attachmentService.upload(AttachmentPurpose.LOGO, file)
            );
        }

        @Test
        @DisplayName("upload file exceeding max size throws")
        void upload_tooLarge_throws() {
            byte[] oversized = new byte[3 * 1024 * 1024]; // 3MB > 2MB limit
            MockMultipartFile file = new MockMultipartFile(
                    "file", "big.png", "image/png", oversized);

            assertThrows(ResourceException.class, () ->
                attachmentService.upload(AttachmentPurpose.LOGO, file)
            );
        }
    }

    @Nested
    @DisplayName("delete")
    class DeleteTests {

        @Test
        @DisplayName("delete removes the record and underlying file")
        void delete_removesRecordAndFile() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "logo.png", "image/png", "bytes".getBytes());
            AttachmentResponse uploaded = attachmentService.upload(AttachmentPurpose.LOGO, file);

            attachmentService.delete(uploaded.getId());

            assertThrows(ResourceException.class, () ->
                attachmentService.getById(uploaded.getId())
            );
        }
    }
}
