package com.mtbs.notification.provider.email;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class EmailMessage {
    private final String to;
    private final String from;
    private final String fromName;
    private final String subject;
    private final String htmlBody;
    private final byte[] pdfAttachment;
    private final String pdfFileName;
}