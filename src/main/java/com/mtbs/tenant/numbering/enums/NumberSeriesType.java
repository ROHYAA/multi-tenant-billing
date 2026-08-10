package com.mtbs.tenant.numbering.enums;

/**
 * Document types that draw from a NumberSeries. Unlike bill templates,
 * new series types inherently need new business logic (a CreditNoteService,
 * a QuotationService, ...) so this stays a compile-time enum rather than a
 * DB-driven catalog.
 */
public enum NumberSeriesType {
    INVOICE
}
