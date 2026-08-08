package com.mtbs.notification.provider.email;

import com.mtbs.notification.config.NotificationProperties;
import com.mtbs.notification.domain.NotificationChannel;
import com.mtbs.notification.domain.NotificationRequest;
import com.mtbs.notification.port.EmailPort;
import com.mtbs.notification.port.NotificationPort;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class SmtpEmailProvider implements EmailPort, NotificationPort {

    private final JavaMailSender mailSender;
    private final NotificationProperties properties;

    private static final Map<String, String> SUBJECT_MAP = Map.ofEntries(
            Map.entry("auth/welcome", "Welcome to MTBS — Your account is ready"),
            Map.entry("auth/login-alert", "New login to your account"),
            Map.entry("auth/password-changed", "Your password was changed"),
            Map.entry("auth/password-reset", "Reset your password"),
            Map.entry("onboarding/onboarding-completed", "Welcome to MTBS — Your account is ready"),
            Map.entry("subscription/trial-started", "Your free trial has started"),
            Map.entry("subscription/trial-ending", "Your trial ends in 3 days"),
            Map.entry("subscription/trial-expired", "Your trial has expired"),
            Map.entry("subscription/activated", "Subscription activated — Welcome aboard"),
            Map.entry("subscription/cancelled", "Your subscription has been cancelled"),
            Map.entry("subscription/expired", "Your subscription has expired"),
            Map.entry("subscription/renewed", "Your subscription has been renewed"),
            Map.entry("plan/upgraded", "You've upgraded your plan"),
            Map.entry("plan/downgraded", "Your plan has been downgraded"),
            Map.entry("billing/invoice-generated", "Invoice ready"),
            Map.entry("billing/invoice-paid", "Payment received — Thank you"),
            Map.entry("billing/invoice-overdue", "Action required: Invoice overdue"),
            Map.entry("billing/payment-success", "Payment confirmed — Thank you"),
            Map.entry("billing/payment-failed", "Payment failed — Action required"),
            Map.entry("billing/payment-retry", "Retrying your payment"),
            Map.entry("billing/payment-refunded", "Refund processed successfully"),
            Map.entry("usage/limit-warning", "Usage limit warning"),
            Map.entry("usage/limit-reached", "Usage limit reached"),
            Map.entry("business/invoice-sent", "Invoice received"),
            Map.entry("business/payment-received", "Payment received")
    );

    @Override
    public NotificationChannel supportedChannel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public void deliver(NotificationRequest request, String renderedContent) {
        EmailMessage message = EmailMessage.builder()
                .to(request.getRecipient())
                .from(properties.getFromAddress())
                .fromName(properties.getFromName())
                .subject(buildSubject(request.getTemplateName()))
                .htmlBody(renderedContent)
                .pdfAttachment(request.getPdfAttachment())
                .pdfFileName(request.getPdfFileName())
                .build();
        send(message);
    }

    @Override
    public void send(EmailMessage message) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setTo(message.getTo());
            helper.setFrom(new InternetAddress(message.getFrom(), message.getFromName()));
            helper.setSubject(message.getSubject());
            helper.setText(message.getHtmlBody(), true);

            if (message.getPdfAttachment() != null && message.getPdfAttachment().length > 0) {
                String fileName = message.getPdfFileName() != null ? message.getPdfFileName() : "attachment.pdf";
                helper.addAttachment(fileName, new ByteArrayResource(message.getPdfAttachment()), "application/pdf");
                log.debug("PDF attached: filename={}, bytes={}", fileName, message.getPdfAttachment().length);
            }

            mailSender.send(mimeMessage);
            log.debug("Email sent successfully: to={}, subject={}", message.getTo(), message.getSubject());
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("Failed to send email: to={}, error={}", message.getTo(), e.getMessage(), e);
            throw new RuntimeException("Failed to send email", e);
        }
    }

    private String buildSubject(String templateName) {
        String subject = SUBJECT_MAP.get(templateName);
        return subject != null ? subject : "MTBS Notification";
    }
}