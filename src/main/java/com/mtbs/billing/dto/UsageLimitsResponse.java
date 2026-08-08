package com.mtbs.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UsageLimitsResponse {

    private ApiCallsUsage apiCalls;
    private UsersUsage users;
    private StorageUsage storage;
    private Instant periodStart;
    private Instant periodEnd;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApiCallsUsage {
        private long used;
        private long limit;
        private long remaining;
        private double usagePercent;
        private double remainingPercent;
        private boolean unlimited;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UsersUsage {
        private long used;
        private long limit;
        private long remaining;
        private double usagePercent;
        private double remainingPercent;
        private boolean unlimited;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StorageUsage {
        private long usedBytes;
        private BigDecimal usedMb;
        private BigDecimal usedGb;
        private BigDecimal limitGb;
        private BigDecimal remainingGb;
        private double usagePercent;
        private double remainingPercent;
        private boolean unlimited;
    }
}