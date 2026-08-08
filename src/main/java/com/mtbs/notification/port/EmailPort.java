package com.mtbs.notification.port;

import com.mtbs.notification.provider.email.EmailMessage;

public interface EmailPort {
    void send(EmailMessage message);
}