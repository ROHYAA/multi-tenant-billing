package com.mtbs.tenant.attachment.enums;

/**
 * What an uploaded file is used for. SIGNATURE/STAMP/QR_CODE are reserved
 * for when those features are built — no ShopSettings field references
 * them yet, but the upload/store/serve/delete infrastructure already
 * supports any purpose.
 */
public enum AttachmentPurpose {
    LOGO,
    SIGNATURE,
    STAMP,
    QR_CODE,
    OTHER
}
