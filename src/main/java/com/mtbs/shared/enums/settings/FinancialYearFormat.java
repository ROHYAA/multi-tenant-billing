package com.mtbs.shared.enums.settings;

/**
 * Controls whether/how a financial-year segment is embedded in generated
 * document numbers (see NumberSeriesService).
 *
 * NONE     -> no FY segment, e.g. "INV-0001"
 * YYYY     -> calendar year of the FY start, e.g. "INV-2026-0001"
 * YYYY_YY  -> Indian FY short form, e.g. "INV-2026-27-0001"
 */
public enum FinancialYearFormat {
    NONE,
    YYYY,
    YYYY_YY
}
