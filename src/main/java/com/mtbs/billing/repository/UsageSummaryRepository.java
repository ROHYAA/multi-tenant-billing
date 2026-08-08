package com.mtbs.billing.repository;

import com.mtbs.billing.entity.UsageSummary;
import com.mtbs.shared.enums.billing.UsageMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface UsageSummaryRepository extends JpaRepository<UsageSummary, Long> {

    List<UsageSummary> findBySubscriptionIdAndIsBilledFalse(Long subscriptionId);

    Optional<UsageSummary> findBySubscriptionIdAndMetricTypeAndBillingPeriodStartAndBillingPeriodEnd(
            Long subscriptionId, UsageMetric metricType, Instant start, Instant end);

    List<UsageSummary> findBySubscriptionIdAndBillingPeriodStartAndBillingPeriodEnd(
            Long subscriptionId, Instant start, Instant end);
}
