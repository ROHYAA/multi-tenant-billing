package com.mtbs.shared.event.billing;

import com.mtbs.shared.enums.notification.NotificationEvent;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCapturedEvent {
    private NotificationEvent eventType;
    private Long tenantId;
    private Long invoiceId;
}