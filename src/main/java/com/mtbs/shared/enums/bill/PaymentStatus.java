package com.mtbs.shared.enums.bill;

/**
 * Lifecycle of a single Payment row.
 *
 * PENDING   — money promised, not yet collected (currently only ever set for
 *             method = CREDIT). Does not count toward an invoice's
 *             outstanding balance and never triggers markPaid().
 * CONFIRMED — collected cash. Every non-CREDIT payment is CONFIRMED on
 *             insert; a PENDING credit payment becomes CONFIRMED via
 *             PaymentService.confirmPayment() once actually collected.
 * CANCELLED — reserved for a future "cancel a pending credit promise"
 *             action; not set by any code path yet.
 */
public enum PaymentStatus {
    PENDING,
    CONFIRMED,
    CANCELLED
}
