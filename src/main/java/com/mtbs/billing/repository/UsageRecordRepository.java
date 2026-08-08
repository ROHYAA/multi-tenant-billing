package com.mtbs.billing.repository;

import com.mtbs.billing.entity.UsageRecord;
import com.mtbs.shared.enums.billing.UsageMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface UsageRecordRepository extends JpaRepository<UsageRecord, Long> {

    List<UsageRecord> findByMetricTypeAndBillingPeriodStartAndBillingPeriodEnd(
            UsageMetric type, Instant start, Instant end);

    @Query("SELECT COALESCE(SUM(u.quantity), 0) FROM UsageRecord u " +
           "WHERE u.metricType = :metricType " +
           "AND u.billingPeriodStart = :start " +
           "AND u.billingPeriodEnd = :end " +
           "AND u.deleted = false")
    Long sumQuantityByMetricTypeAndBillingPeriodStartAndBillingPeriodEnd(
            @Param("metricType") UsageMetric metricType,
            @Param("start") Instant start,
            @Param("end") Instant end);

    List<UsageRecord> findBySubscriptionIdAndBillingPeriodStartAndBillingPeriodEnd(
            Long subscriptionId, Instant start, Instant end);

    @Query("SELECT COALESCE(SUM(u.valueBytes), 0) FROM UsageRecord u " +
           "WHERE u.tenantId = :tenantId AND u.metricType = :metric " +
           "AND u.billingPeriodStart >= :start AND u.deleted = false")
    Long sumValueBytesByTenantAndMetricAndPeriod(
            @Param("tenantId") Long tenantId,
            @Param("metric") UsageMetric metric,
            @Param("start") Instant start);

    @Query("SELECT COALESCE(SUM(u.quantity), 0) FROM UsageRecord u " +
           "WHERE u.tenantId = :tenantId AND u.metricType = :metric " +
           "AND u.billingPeriodStart >= :start AND u.deleted = false")
    Long sumCountByTenantAndMetricSince(
            @Param("tenantId") Long tenantId,
            @Param("metric") UsageMetric metric,
            @Param("start") Instant start);

    Optional<UsageRecord> findByTenantIdAndMetricTypeAndBillingPeriodStart(
            Long tenantId, UsageMetric metricType, Instant periodStart);

    List<UsageRecord> findByTenantIdAndMetricTypeAndBillingPeriodStartBetweenAndIsBilledFalse(
            Long tenantId, UsageMetric metricType, Instant start, Instant end);

    List<UsageRecord> findByTenantIdAndMetricTypeAndBillingPeriodStartBetween(
            Long tenantId, UsageMetric metricType, Instant start, Instant end);

    @Modifying
    @Query("UPDATE UsageRecord u SET u.isBilled = true WHERE u.tenantId = :tenantId " +
           "AND u.metricType = :metricType AND u.billingPeriodStart >= :start AND u.isBilled = false")
    int markAsBilled(@Param("tenantId") Long tenantId,
                     @Param("metricType") UsageMetric metricType,
                     @Param("start") Instant start);

    @Query(value = "SELECT DATE(created_at) as date, COUNT(*) as count " +
           "FROM usage_records " +
           "WHERE tenant_id = :tenantId AND metric_type = :metric " +
           "AND created_at >= :startDate " +
           "AND deleted = false " +
           "GROUP BY DATE(created_at) " +
           "ORDER BY date", nativeQuery = true)
    List<Object[]> countByMetricGroupedByDate(
            @Param("tenantId") Long tenantId,
            @Param("metric") String metric,
            @Param("startDate") Instant startDate);

    @Query(value = "SELECT DATE(created_at) as date, SUM(value_bytes) as total_bytes " +
           "FROM usage_records " +
           "WHERE tenant_id = :tenantId AND metric_type = :metric " +
           "AND created_at >= :startDate " +
           "AND deleted = false " +
           "GROUP BY DATE(created_at) " +
           "ORDER BY date", nativeQuery = true)
    List<Object[]> sumStorageByMetricGroupedByDate(
            @Param("tenantId") Long tenantId,
            @Param("metric") String metric,
            @Param("startDate") Instant startDate);
}
