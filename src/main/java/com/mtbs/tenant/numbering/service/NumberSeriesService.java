package com.mtbs.tenant.numbering.service;

import com.mtbs.shared.enums.settings.FinancialYearFormat;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.tenant.numbering.entity.NumberSeries;
import com.mtbs.tenant.numbering.enums.NumberSeriesType;
import com.mtbs.tenant.numbering.repository.NumberSeriesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Generates document numbers (e.g. "INV-2026-27-0001") from a NumberSeries
 * row, replacing what used to be hardcoded directly in BillService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NumberSeriesService {

    private final NumberSeriesRepository numberSeriesRepository;

    @Transactional
    public String nextNumber(NumberSeriesType seriesType) {
        NumberSeries series = numberSeriesRepository
                .findBySeriesTypeAndIsActiveTrueForUpdate(seriesType)
                .orElseThrow(() -> ResourceException.notFound("Active NumberSeries", seriesType.name()));

        long next = series.getCurrentNumber() + 1;
        series.setCurrentNumber(next);
        // Managed entity — dirty-checked and flushed at transaction commit,
        // while the PESSIMISTIC_WRITE lock from the finder above is held.

        String formatted = series.getPrefix() + "-" + financialYearSegment(series.getFinancialYearFormat()) + String.format("%04d", next);
        log.debug("Generated number for series={}: {}", seriesType, formatted);
        return formatted;
    }

    private String financialYearSegment(FinancialYearFormat format) {
        if (format == FinancialYearFormat.NONE) {
            return "";
        }
        // Indian financial year: April 1 - March 31.
        LocalDate today = LocalDate.now();
        int fyStartYear = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;

        return switch (format) {
            case YYYY -> fyStartYear + "-";
            case YYYY_YY -> fyStartYear + "-" + String.format("%02d", (fyStartYear + 1) % 100) + "-";
            case NONE -> "";
        };
    }
}
