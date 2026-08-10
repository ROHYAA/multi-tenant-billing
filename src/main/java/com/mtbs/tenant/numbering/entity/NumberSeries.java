package com.mtbs.tenant.numbering.entity;

import com.mtbs.shared.entity.AuditableEntity;
import com.mtbs.shared.enums.settings.FinancialYearFormat;
import com.mtbs.tenant.numbering.enums.NumberSeriesType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Document numbering state for one series (INVOICE for V1). One row per
 * type per shop. currentNumber is incremented atomically via a native
 * query (NumberSeriesRepository.incrementAndReturn) — never read-then-write
 * in application code, which is exactly the race condition this replaces
 * (BillService used to derive invoice numbers from a live COUNT(*)).
 */
@Entity
@Table(name = "number_series")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NumberSeries extends AuditableEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "series_type", nullable = false, length = 30)
    private NumberSeriesType seriesType;

    @Column(nullable = false, length = 20)
    private String prefix;

    @Enumerated(EnumType.STRING)
    @Column(name = "financial_year_format", nullable = false, length = 20)
    private FinancialYearFormat financialYearFormat;

    @Column(name = "starting_number", nullable = false)
    private Long startingNumber;

    @Column(name = "current_number", nullable = false)
    private Long currentNumber;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
