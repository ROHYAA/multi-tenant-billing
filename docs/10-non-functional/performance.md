---
version: 1.0
date: 2026-05-17
author: Manoj Pandi
status: Production Ready
tags:
  - performance
  - query-optimization
  - indexing
  - n+1-queries
  - caching-strategy
  - database-tuning
  - pagination
  - lazy-loading
  - dto-projection
related_documents:
  - ./scalability.md
  - ./observability.md
  - ./redis-data-model.md
  - ./payment-processing.md
---

# Performance: Query Optimization, Indexing, & Caching

## Executive Summary

**Performance** is the system's responsiveness. MTBS targets sub-500ms response times for typical operations (query subscriptions, create invoice) through strategic indexing, DTO projection, lazy-loading prevention, and Redis caching.

This document covers:
1. **Query Optimization** — N+1 prevention, eager loading, DTO projection
2. **Database Indexing** — Strategic indexes on hot paths (subscription lookups, invoice filtering)
3. **Caching Patterns** — Cache-aside, eviction strategies, TTL tuning
4. **Pagination** — Efficient offset/limit vs cursor-based pagination
5. **Monitoring & Profiling** — Identify slow queries, latency bottlenecks

Key insight: 90% of performance problems are database-related. Optimize queries first; cache second.

---

## Context / Problem

### Historical Problem: N+1 Query Explosion

**Scenario: List all subscriptions for a tenant**

```
Query 1: SELECT * FROM subscriptions WHERE tenant_id = 't1'
         → 500 subscriptions returned

Loop: for each subscription:
  Query 2-501: SELECT * FROM plans WHERE id = subscription.plan_id
               (500 separate queries!)

Result: 501 database round-trips, 3 seconds latency
```

**Bad Query Behavior:**
```sql
-- Subscription entity has @ManyToOne(fetch=FetchType.EAGER) Plan
-- JPA naively executes:

SELECT s.* FROM subscriptions s WHERE s.tenant_id = 't1';
-- 500 results

-- For each result:
SELECT p.* FROM plans p WHERE p.id = 500;  -- Query 501
SELECT p.* FROM plans p WHERE p.id = 501;  -- Query 502
... (repeat 500 times)

Result: 501 queries, ~500ms per query = 250 seconds!
```

**Solution: Eager Loading with JOIN**

```sql
SELECT s.*, p.* FROM subscriptions s
LEFT JOIN plans p ON s.plan_id = p.id
WHERE s.tenant_id = 't1';
-- 1 query, 1 result set with plan data denormalized

Result: 1 database round-trip, 50ms latency (5x improvement)
```

### Root Cause: Lazy Loading by Default

```java
@Entity
public class Subscription {
    @Id String id;
    String planId;
    
    @ManyToOne(fetch = FetchType.LAZY)  // Default!
    Plan plan;
}

// When you access subscription.getPlan():
subscriptionService.getSubscription("s1");
// SELECT * FROM subscriptions WHERE id = 's1';
// ↓ plan not loaded yet (lazy)

subscriptions.get(0).getPlan();  // BOOM! Extra query here
// SELECT * FROM plans WHERE id = plan_id;
```

---

## Dependencies

### Inbound (What benefits from performance)
- **Frontend** — Faster responses = better UX, lower bounce rate
- **Mobile Client** — Cellular networks especially sensitive to latency
- **Reporting Jobs** — Overnight batch processing needs indexing

### Outbound (What performance depends on)
- **PostgreSQL** — Query planner, indexes, connection pool
- **Redis** — Cache layer for frequently-accessed data
- **Network** — Latency between app server and database

### Configuration
```yaml
spring:
  jpa:
    properties:
      hibernate:
        # Enable batch size loading
        default_batch_fetch_size: 25
        # Log slow queries
        show_sql: false  # Enable in dev only
        format_sql: true
        use_sql_comments: true
        jdbc:
          batch_size: 20
          fetch_size: 50
        
  datasource:
    hikari:
      # Connection pool
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      
# Logging slow queries
logging:
  level:
    org.springframework.data: DEBUG
    org.hibernate.stat: DEBUG
    org.hibernate.SQL: DEBUG
```

---

## Design / Implementation

### Layer 1: Query Optimization

#### Pattern 1: Eager Loading (JOIN)

**Bad (N+1):**
```java
@Entity
public class Subscription {
    @ManyToOne(fetch = FetchType.LAZY)
    Plan plan;  // Default lazy, causes N+1
}

@Service
public SubscriptionService {
    public List<SubscriptionResponse> getSubscriptions(String tenantId) {
        List<Subscription> subs = subscriptionRepository
            .findAllByTenantId(tenantId);  // 1 query
        
        return subs.stream().map(s -> 
            new SubscriptionResponse(
                s.getId(),
                s.getPlan().getName()  // N queries (lazy load each plan)
            )
        ).collect(toList());
    }
}
```

**Good (Eager Load):**
```java
@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, String> {
    
    // Use @EntityGraph to force eager loading
    @EntityGraph(attributePaths = {"plan"})
    List<Subscription> findAllByTenantId(String tenantId);
    
    // Or use JOIN FETCH in custom query
    @Query("SELECT s FROM Subscription s " +
           "LEFT JOIN FETCH s.plan p " +
           "WHERE s.tenantId = :tenantId")
    List<Subscription> findAllByTenantIdEager(@Param("tenantId") String tenantId);
}

@Service
public SubscriptionService {
    public List<SubscriptionResponse> getSubscriptions(String tenantId) {
        List<Subscription> subs = subscriptionRepository
            .findAllByTenantIdEager(tenantId);  // 1 query with JOIN
        
        return subs.stream().map(s -> 
            new SubscriptionResponse(
                s.getId(),
                s.getPlan().getName()  // No extra queries
            )
        ).collect(toList());
    }
}
```

**Execution Plan Difference:**

```sql
-- BAD (N+1):
SELECT s.* FROM subscriptions s WHERE s.tenant_id = 't1';
-- 500 results

for result in results:
  SELECT p.* FROM plans p WHERE p.id = result.plan_id;
  -- 500 additional queries

Total: 501 queries, 3000ms


-- GOOD (JOIN):
SELECT s.*, p.* FROM subscriptions s
LEFT JOIN plans p ON s.plan_id = p.id
WHERE s.tenant_id = 't1';
-- 500 results with plan data included

Total: 1 query, 50ms
```

#### Pattern 2: DTO Projection (Select Only Needed Fields)

**Bad (Select All Columns):**
```java
// Loads all fields: id, planId, status, createdAt, updatedAt, etc.
List<Subscription> subs = subscriptionRepository
    .findAllByTenantId(tenantId);  // 500 columns × 500 rows = huge

// But response only needs: id, planName, status (3 fields)
```

**Good (Project Only Needed Fields):**
```java
public interface SubscriptionDTO {
    String getId();
    String getPlanName();
    String getStatus();
}

@Repository
public interface SubscriptionRepository {
    @Query("SELECT new com.mtbs.dto.SubscriptionDTO(" +
           "s.id, p.name, s.status) " +
           "FROM Subscription s " +
           "LEFT JOIN s.plan p " +
           "WHERE s.tenantId = :tenantId")
    List<SubscriptionDTO> findAllDTOByTenantId(@Param("tenantId") String tenantId);
}

@Service
public SubscriptionService {
    public List<SubscriptionResponse> getSubscriptions(String tenantId) {
        return subscriptionRepository
            .findAllDTOByTenantId(tenantId)  // Only 3 columns
            .stream()
            .map(dto -> new SubscriptionResponse(
                dto.getId(),
                dto.getPlanName(),
                dto.getStatus()
            ))
            .collect(toList());
    }
}
```

**Network Savings:**
```
Without projection: 500 rows × 20 columns × 50 bytes = 500KB
With projection: 500 rows × 3 columns × 50 bytes = 75KB
Savings: 425KB (85% reduction)

At 10 Mbps: 40ms vs 3ms latency
```

#### Pattern 3: Batch Loading

**Without Batch:**
```java
// Query 1: Get invoices
List<Invoice> invoices = invoiceRepository.findAll();  // 1000 invoices

// Loop: Load payment for each invoice
for (Invoice inv : invoices) {
    Payment payment = paymentRepository.findByInvoiceId(inv.getId());
    // 1000 queries!
}
```

**With Batch Loading:**
```java
@Repository
public interface PaymentRepository {
    // Batch fetch payments for multiple invoices
    @Query("SELECT p FROM Payment p WHERE p.invoiceId IN :invoiceIds")
    List<Payment> findByInvoiceIds(@Param("invoiceIds") List<String> invoiceIds);
}

@Service
public InvoiceService {
    public void processInvoices() {
        List<Invoice> invoices = invoiceRepository.findAll();
        
        // Load all payments in one batch
        List<String> invoiceIds = invoices.stream()
            .map(Invoice::getId)
            .collect(toList());
        
        List<Payment> payments = paymentRepository
            .findByInvoiceIds(invoiceIds);  // 1 query for all payments
        
        Map<String, Payment> paymentMap = payments.stream()
            .collect(toMap(Payment::getInvoiceId, identity()));
        
        // Now access payments O(1) from map
        for (Invoice inv : invoices) {
            Payment payment = paymentMap.get(inv.getId());
        }
    }
}
```

**Comparison:**
```
Without batch: 1000 queries
With batch: 2 queries (invoices + payments)
Improvement: 500x
```

#### Pattern 4: Pagination to Avoid Loading All Rows

**Bad (Load All):**
```java
public List<SubscriptionResponse> getSubscriptions(String tenantId) {
    List<Subscription> all = subscriptionRepository
        .findAllByTenantId(tenantId);  // 50,000 subscriptions!
    
    return all.subList(0, 20);  // Only need 20 for display
    // Loaded 50k, returned 20 (49,980 wasted)
}
```

**Good (Offset/Limit):**
```java
@Repository
public interface SubscriptionRepository {
    @Query("SELECT s FROM Subscription s WHERE s.tenantId = :tenantId " +
           "ORDER BY s.createdAt DESC")
    Page<Subscription> findAllByTenantId(
        @Param("tenantId") String tenantId,
        Pageable pageable);  // Handles LIMIT/OFFSET
}

@Service
public SubscriptionService {
    public Page<SubscriptionResponse> getSubscriptions(
        String tenantId, int pageNum, int pageSize) {
        
        Pageable pageable = PageRequest.of(pageNum, pageSize);
        Page<Subscription> page = subscriptionRepository
            .findAllByTenantId(tenantId, pageable);  // SELECT ... LIMIT 20 OFFSET 0
        
        return page.map(s -> new SubscriptionResponse(s));
    }
}
```

**SQL Generated:**
```sql
SELECT s.* FROM subscriptions s
WHERE s.tenant_id = 't1'
ORDER BY s.created_at DESC
LIMIT 20 OFFSET 0;
-- Loads only 20 rows, not 50,000
```

### Layer 2: Database Indexing

#### Strategic Indexes on Hot Paths

**Analysis: Which queries are slow?**

```sql
-- Enable query logging
SET log_statement = 'all';
SET log_min_duration_statement = 100;  -- Log queries >100ms

-- Run app for 1 hour, collect slow queries
-- Analyze: most common slow queries

Query 1: SELECT * FROM subscriptions WHERE tenant_id = 't1' AND status = 'ACTIVE'
         (Executed 5000 times, avg 450ms) ← HOT PATH

Query 2: SELECT * FROM invoices WHERE subscription_id = 's1' AND created_at > now()-30days
         (Executed 200 times, avg 200ms) ← MEDIUM

Query 3: SELECT * FROM payments WHERE subscription_id = 's1'
         (Executed 1000 times, avg 50ms) ← ACCEPTABLE

Query 4: SELECT * FROM audit_log WHERE action = 'PAYMENT_CAPTURED' AND created_at > now()-1hour
         (Executed 10 times, avg 800ms) ← RARE but SLOW
```

**Indexing Strategy:**

| Query | Index | Reason |
|-------|-------|--------|
| Query 1 (HOT) | `CREATE INDEX idx_sub_tenant_status ON subscriptions(tenant_id, status)` | Composite index for filter + scan |
| Query 1 | `CREATE INDEX idx_sub_tenant ON subscriptions(tenant_id)` | Fall back if status varies |
| Query 2 (MEDIUM) | `CREATE INDEX idx_inv_sub_date ON invoices(subscription_id, created_at DESC)` | Composite index for range query |
| Query 3 (OK) | Maybe no index | 50ms acceptable, add only if Query 3 becomes Query 1 |
| Query 4 (RARE) | No index | Too infrequent to justify index maintenance overhead |

**Index Implementation:**

```java
@Entity
@Table(indexes = {
    @Index(name = "idx_sub_tenant_status", columnList = "tenant_id,status"),
    @Index(name = "idx_sub_plan", columnList = "plan_id"),
    @Index(name = "idx_sub_billing_cycle", columnList = "billing_cycle_end")
})
public class Subscription {
    @Id String id;
    @Column(nullable = false) String tenantId;
    @Column(nullable = false) String planId;
    @Column(nullable = false) SubscriptionStatus status;
    @Column(nullable = false) Instant billingCycleEnd;
    // ... other fields
}

@Entity
@Table(indexes = {
    @Index(name = "idx_inv_sub_created", columnList = "subscription_id,created_at DESC"),
    @Index(name = "idx_inv_status", columnList = "status"),
    @Index(name = "idx_inv_tenant", columnList = "tenant_id")
})
public class Invoice {
    @Id String id;
    @Column(nullable = false) String subscriptionId;
    @Column(nullable = false) String tenantId;
    @Column(nullable = false) InvoiceStatus status;
    @Column(nullable = false) Instant createdAt;
    // ... other fields
}
```

**Flyway Migration:**

```sql
-- V1.20__Add_Performance_Indexes.sql
CREATE INDEX CONCURRENTLY idx_sub_tenant_status 
  ON subscriptions(tenant_id, status);

CREATE INDEX CONCURRENTLY idx_inv_sub_created 
  ON invoices(subscription_id, created_at DESC);

CREATE INDEX CONCURRENTLY idx_payment_sub 
  ON payments(subscription_id);

-- CONCURRENTLY prevents write locks during index creation
```

#### Compound Index Efficiency

**Query:**
```sql
SELECT * FROM subscriptions
WHERE tenant_id = 't1' AND status = 'ACTIVE' AND plan_id = 'plan_pro';
```

**Index Options:**

| Index | Efficiency | Reason |
|-------|-----------|--------|
| `(tenant_id, status, plan_id)` | 100% | All columns in index, full index scan |
| `(tenant_id, status)` | 90% | First 2 columns match, plan_id requires table lookup |
| `(tenant_id, plan_id, status)` | 70% | Leftmost prefix rule: indexes search left-to-right, status not first so filtering slower |
| No index | 0% | Full table scan |

**Column Order Matters (Leftmost Prefix Rule):**

```
Index: (tenant_id, status, plan_id)

Queries that can use this index fully:
✓ WHERE tenant_id = ? (uses 1st column)
✓ WHERE tenant_id = ? AND status = ? (uses 1st + 2nd)
✓ WHERE tenant_id = ? AND status = ? AND plan_id = ? (uses all 3)
✓ WHERE tenant_id = ? AND plan_id = ? (uses 1st, 2nd ignored for filtering but available)

Queries that can't use this index:
✗ WHERE status = ? (doesn't start with tenant_id, no index usage)
✗ WHERE plan_id = ? (doesn't start with tenant_id, no index usage)
✗ WHERE status = ? AND plan_id = ? (doesn't include tenant_id, no index usage)
```

---

### Layer 3: Caching Patterns

#### Pattern 1: Cache-Aside (Lazy Loading)

```java
@Service
public class PlanService {
    
    public Plan getPlan(String planId) {
        // 1. Check cache
        String cacheKey = "plan:" + planId;
        Plan cached = (Plan) redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached;  // Cache hit
        }
        
        // 2. Cache miss: load from DB
        Plan plan = planRepository.findById(planId)
            .orElseThrow(() -> new ResourceException.notFound());
        
        // 3. Store in cache (5 hour TTL)
        redisTemplate.opsForValue().set(
            cacheKey, plan, Duration.ofHours(5));
        
        return plan;
    }
}
```

**Behavior:**
```
First request: Cache miss → DB query → 100ms → cache miss
Second request (within 5 hours): Cache hit → 5ms
Miss ratio after 1 hour: 5% (new plans added slowly)
Performance: Average 10ms vs 100ms (10x improvement)
```

#### Pattern 2: Write-Through (Update Cache + DB)

```java
@Service
public class PlanService {
    
    public Plan updatePlan(String planId, UpdatePlanRequest req) {
        // 1. Update database
        Plan updated = planRepository.update(planId, req.getName(), req.getPrice());
        
        // 2. Update cache with new value
        String cacheKey = "plan:" + planId;
        redisTemplate.opsForValue().set(cacheKey, updated, Duration.ofHours(5));
        
        return updated;
    }
}
```

**Behavior:**
```
Admin updates plan name
→ Database updated
→ Cache invalidated (new value stored)
→ Next read: Cache hit with new value
No stale cache data
```

#### Pattern 3: Distributed Cache Invalidation (Event-Based)

```java
@Service
public class PlanService {
    
    public void updatePlan(String planId, UpdatePlanRequest req) {
        // 1. Update database
        Plan updated = planRepository.update(planId, req);
        
        // 2. Publish cache invalidation event
        cacheEvictionPublisher.publish(new CacheEvictionEvent(
            "plan:" + planId,
            CacheEvictionType.INVALIDATE
        ));
    }
}

@Component
public class CacheEvictionListener {
    
    @EventListener(condition = "#event.type == 'INVALIDATE'")
    public void onCacheInvalidation(CacheEvictionEvent event) {
        // All app servers receive this event
        // Redis cache invalidated across all instances
        redisTemplate.delete(event.getKey());
    }
}
```

**Benefit:** Multi-instance cache coherency.

#### Pattern 4: Bulk Cache Warming (Pre-Load)

```java
@Component
public class CacheWarmer {
    
    @Scheduled(fixedRate = 3600000)  // Every hour
    public void warmPlanCache() {
        // Load all plans into cache proactively
        List<Plan> allPlans = planRepository.findAll();
        
        for (Plan plan : allPlans) {
            String cacheKey = "plan:" + plan.getId();
            redisTemplate.opsForValue().set(
                cacheKey, plan, Duration.ofHours(5));
        }
        
        log.info("Warmed {} plans in cache", allPlans.size());
    }
}
```

**Benefit:** Eliminates cold-start misses for frequently-accessed data.

---

## Performance Profiling

### Identifying Bottlenecks

**Step 1: Collect Metrics**

```java
@Component
@Aspect
public class PerformanceAspect {
    
    @Around("@annotation(Monitored)")
    public Object monitor(ProceedingJoinPoint pjp) throws Throwable {
        String methodName = pjp.getSignature().getName();
        
        long start = System.currentTimeMillis();
        try {
            return pjp.proceed();
        } finally {
            long duration = System.currentTimeMillis() - start;
            
            if (duration > 500) {  // Log slow calls
                log.warn("SLOW: {} took {}ms", methodName, duration);
            }
            
            meterRegistry.timer("method.latency",
                "method", methodName
            ).record(duration, TimeUnit.MILLISECONDS);
        }
    }
}

@Service
public class SubscriptionService {
    
    @Monitored
    public List<SubscriptionResponse> getSubscriptions(String tenantId) {
        // Latency tracked
    }
}
```

**Step 2: Analyze Prometheus Metrics**

```
Query: histogram_quantile(0.95, method.latency{method="getSubscriptions"})
Result: p95 latency = 850ms ← SLOW

Sub-method profiling:
  previewUpgrade: 150ms ← OK
  calculateCredit: 200ms ← OK
  priceResolution: 500ms ← SLOW!
```

**Step 3: Profile Slow Method**

```java
// Enable detailed logging
public List<Plan> resolvePrices() {
    log.debug("Starting resolvePrices");
    
    long t1 = System.currentTimeMillis();
    List<Plan> plans = planRepository.findAll();
    long t2 = System.currentTimeMillis();
    log.debug("DB query took {}ms", t2 - t1);  // 450ms
    
    long t3 = System.currentTimeMillis();
    plans.forEach(p -> p.calculateMonthlyPrice());
    long t4 = System.currentTimeMillis();
    log.debug("Price calc took {}ms", t4 - t3);  // 50ms
    
    return plans;
}

// Result: DB query is bottleneck (450ms)
// Solution: Add index on plans, use caching
```

---

## Known Issues / Limitations

1. **Premature Optimization** — Don't optimize before profiling. 90% of performance issues are at the DB layer. Optimizing application code first wastes time.

2. **Index Maintenance Overhead** — Every index slows down writes (INSERT, UPDATE, DELETE). More indexes = slower writes. Balance: hot read paths vs write penalty.

3. **Cache Coherency** — If cache not invalidated after write, stale data served. Requires disciplined invalidation policy.

4. **Pagination Offset Inefficiency** — `LIMIT 100 OFFSET 1000000` scans 1M rows then discards them. Use cursor-based pagination for large offsets.

5. **JOIN Explosion** — Fetching subscriptions with plan + invoice + payment: `SELECT ... LEFT JOIN ... LEFT JOIN ...` returns Cartesian product (1000 × 10 × 5 = 50k rows, actually 1.5 MB). Use separate queries or DTO projection.

6. **Query Plan Instability** — PostgreSQL query planner chooses index based on statistics. After data changes, plan might change. `ANALYZE` table to refresh stats.

---

## Future Improvements

1. **Cursor-Based Pagination** — Instead of OFFSET, use "last_id" cursor. Efficient for large datasets and real-time data.

2. **Materialized Views** — Pre-compute expensive aggregations (e.g., tenant revenue). Refresh on schedule.

3. **Query Result Caching** — Cache entire query results (not just entities). TTL-based invalidation.

4. **Database Partitioning** — Partition invoices by date (2026-01, 2026-02, ...). Faster range queries (only scan relevant partitions).

5. **Connection Pool Tuning** — Dynamic pool size based on load. Reduce when quiet, increase for spikes.

6. **Prepared Statement Pooling** — Cache compiled SQL statements. Avoids parsing overhead.

---

## Related Documents

- [scalability.md](./scalability.md) — Load distribution and caching strategy
- [redis-data-model.md](./redis-data-model.md) — Cache structure and data models
- [observability.md](./observability.md) — Metrics and query profiling
