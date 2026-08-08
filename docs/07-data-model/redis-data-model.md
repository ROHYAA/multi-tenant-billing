---
version: 1.0
date: 2026-05-17
author: Manoj Pandi
status: Production Ready
tags:
  - redis
  - caching
  - data-model
  - distributed-cache
  - ttl
  - eviction
  - json-serialization
  - cache-keys
related_documents:
  - ./scalability.md
  - ./performance.md
  - ./observability.md
  - ./configuration-reference.md
---

# Redis Data Model: Cache Structure, TTL, & Serialization

## Executive Summary

**Redis** is an in-memory data store for distributed caching across MTBS application instances. This document defines:
1. **Cache Key Naming** — conventions for consistent key generation
2. **Data Models** — what gets cached, serialization format, structure
3. **TTL Strategy** — time-to-live values for different cache types
4. **Eviction Policies** — what happens when Redis memory fills
5. **Cache Invalidation** — how to keep cached data consistent
6. **Monitoring** — cache hit/miss rates, memory usage

Key insight: Redis is a shared resource across all app servers. Coordinate cache invalidation across instances to maintain coherency.

---

## Context / Problem

### Historical Problem: Per-Instance Caching

**Before Redis:**
```
Each app server had local in-memory caches:
  Server 1: {cache} = {plan:1 → PRO plan object}
  Server 2: {cache} = {plan:1 → (outdated PRO plan object)}
  
Admin updates PRO plan: price 299 → 399

Server 1 gets new cache (hit after cache miss)
Server 2 still serves old cache (stale data)

Result: Inconsistent billing across users depending on which server served them
```

**With Redis (distributed cache):**
```
All servers share Redis:
  Redis: {plan:1 → {name: "PRO", price: 299}}
  
Server 1 & 2 both get same value (cache hit)

Admin updates PRO plan: price 299 → 399
→ Redis cache invalidated (key deleted)
→ Both servers cache miss, reload from DB
→ Redis: {plan:1 → {name: "PRO", price: 399}}
→ All servers consistent
```

---

## Dependencies

### Inbound (What uses Redis)
- **PlanService** — caches plan objects (rarely change)
- **SubscriptionService** — caches subscription queries (frequent, short TTL)
- **Session Store** — HTTP session data (Spring Session with Redis)
- **Rate Limiting** — token bucket tracking (sliding window)

### Outbound (What Redis depends on)
- **Redis Server** — in-memory data store
- **Jedis/Lettuce** — Java client libraries
- **Spring Data Redis** — Spring integration
- **Network** — TCP connection from app servers to Redis

### Configuration
```yaml
spring:
  redis:
    host: redis-cluster.internal
    port: 6379
    password: ${REDIS_PASSWORD}
    timeout: 2000
    database: 0
    ssl: false
    
    jedis:
      pool:
        max-active: 20
        max-idle: 10
        min-idle: 2
    
  cache:
    type: redis
    redis:
      time-to-live: 300000  # 5 min default
  
  session:
    store-type: redis
    timeout: 1800s
```

---

## Design / Implementation

### Layer 1: Cache Key Naming

#### Conventions

```
Pattern: <entity-type>:<entity-id>[:<qualifier>]

Examples:
plan:plan-1                    (Plan object)
plan:plan-1:price-monthly     (Specific field)
subscription:s-123            (Subscription object)
subscription:s-123:invoices   (Related collection)
user:u-42:session            (User session)
tenant:t1:config             (Tenant configuration)
rate-limit:user-42:api-calls (Rate limiting token bucket)
notification:pending-queue   (Queue for notification events)
```

#### Key Generation Logic

```java
@Component
public class CacheKeyGenerator {
    
    /**
     * Generate cache key for entity with optional qualifier.
     * Examples:
     *   generateKey("plan", "plan-1") → "plan:plan-1"
     *   generateKey("plan", "plan-1", "pricing") → "plan:plan-1:pricing"
     */
    public String generateKey(String entityType, String entityId, String... qualifiers) {
        StringBuilder key = new StringBuilder(entityType).append(":").append(entityId);
        
        for (String qualifier : qualifiers) {
            key.append(":").append(qualifier);
        }
        
        return key.toString();
    }
    
    // Convenience methods
    public String planKey(String planId) {
        return generateKey("plan", planId);
    }
    
    public String subscriptionKey(String subscriptionId) {
        return generateKey("subscription", subscriptionId);
    }
    
    public String subscriptionInvoicesKey(String subscriptionId) {
        return generateKey("subscription", subscriptionId, "invoices");
    }
    
    public String sessionKey(String sessionId) {
        return generateKey("session", sessionId);
    }
    
    public String rateLimitKey(String userId) {
        return generateKey("rate-limit", userId, "api-calls");
    }
}
```

#### Namespace Isolation per Tenant

For multi-tenancy, include tenant context:

```
Pattern: tenant:<tenantId>:<entity-type>:<entity-id>

Examples:
tenant:t1:subscription:s-123
tenant:t1:invoice:inv-999
tenant:t2:subscription:s-456  (Different tenant, separate cache)
```

**Implementation:**

```java
@Component
public class TenantAwareCacheKeyGenerator {
    
    private final TenantContext tenantContext;
    private final CacheKeyGenerator generator;
    
    public String generateKey(String entityType, String entityId) {
        String tenantId = tenantContext.getCurrentTenantId();
        return "tenant:" + tenantId + ":" + entityType + ":" + entityId;
    }
    
    public String subscriptionKey(String subscriptionId) {
        String tenantId = tenantContext.getCurrentTenantId();
        return generateKey("subscription", subscriptionId);
    }
}
```

---

### Layer 2: Data Models & Serialization

#### Plan Cache Model

```java
@Data
@Cacheable(value = "plans", key = "#planId")
public class CachedPlan {
    private String id;
    private String name;
    private PlanType type;  // FREE, PRO, ENTERPRISE
    private BigDecimal priceMonthly;
    private BigDecimal priceAnnual;
    private List<String> features;  // ["api-access", "priority-support"]
    private Integer trialDays;
    private Integer maxUsers;
    private Boolean active;
    private Instant createdAt;
    private Instant updatedAt;
    
    // Can be reconstructed from DB if cached version missing
}

@Service
@Slf4j
public class PlanService {
    
    @Cacheable(value = "plans", key = "#planId", unless = "#result == null")
    public CachedPlan getPlan(String planId) {
        // DB call
        Plan plan = planRepository.findById(planId)
            .orElseThrow(() -> new ResourceException.notFound());
        
        // Convert to cached model
        return new CachedPlan(
            plan.getId(),
            plan.getName(),
            plan.getType(),
            plan.getPriceMonthly(),
            plan.getPriceAnnual(),
            plan.getFeatures(),
            plan.getTrialDays(),
            plan.getMaxUsers(),
            plan.isActive(),
            plan.getCreatedAt(),
            plan.getUpdatedAt()
        );
    }
    
    @CacheEvict(value = "plans", key = "#planId")
    public void invalidatePlan(String planId) {
        log.info("Invalidated plan cache: {}", planId);
    }
    
    @CacheEvict(value = "plans", allEntries = true)
    public void invalidateAllPlans() {
        log.info("Invalidated all plan caches");
    }
}
```

**Redis Representation (Serialized as JSON):**

```json
{
  "id": "plan-1",
  "name": "PRO",
  "type": "PRO",
  "priceMonthly": 299.00,
  "priceAnnual": 2999.00,
  "features": ["api-access", "priority-support", "analytics"],
  "trialDays": 14,
  "maxUsers": 100,
  "active": true,
  "createdAt": "2025-01-01T00:00:00Z",
  "updatedAt": "2026-05-17T14:30:00Z"
}
```

#### Subscription Cache Model

```java
@Data
public class CachedSubscription {
    private String id;
    private String planId;
    private SubscriptionStatus status;  // ACTIVE, TRIALING, CANCELLED
    private BillingCycle billingCycle;  // MONTHLY, ANNUAL
    private Instant currentPeriodStart;
    private Instant currentPeriodEnd;
    private Instant trialStart;
    private Instant trialEnd;
    private Instant cancelledAt;
    private Boolean cancelAtPeriodEnd;
    private Instant createdAt;
    private Instant updatedAt;
}

@Service
public class SubscriptionService {
    
    @Cacheable(
        value = "subscriptions",
        key = "{ #tenantId, #subscriptionId }",
        unless = "#result == null"
    )
    public CachedSubscription getSubscription(String tenantId, String subscriptionId) {
        // Verify tenancy
        verifyTenant(tenantId);
        
        // DB call
        Subscription sub = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new ResourceException.notFound());
        
        return new CachedSubscription(/* ... */);
    }
}
```

**TTL: 10 minutes** (subscriptions change frequently, don't cache long).

#### Invoice Query Cache

```java
@Data
public class CachedInvoiceList {
    private List<InvoiceDTO> invoices;
    private Integer totalCount;
    private Instant cachedAt;
}

@Service
public class InvoiceService {
    
    @Cacheable(
        value = "invoice-lists",
        key = "{ #tenantId, #subscriptionId, #pageNum }",
        unless = "#result == null"
    )
    public Page<InvoiceDTO> getInvoices(
        String tenantId, String subscriptionId, int pageNum, int pageSize) {
        
        Pageable pageable = PageRequest.of(pageNum, pageSize);
        return invoiceRepository.findBySubscriptionId(subscriptionId, pageable)
            .map(InvoiceDTO::fromEntity);
    }
}
```

**TTL: 5 minutes** (invoices rarely change after creation).

#### Session Data Model

```java
@Data
public class CachedSession {
    private String sessionId;
    private String userId;
    private String tenantId;
    private List<String> roles;  // ["ADMIN", "USER"]
    private Map<String, Object> attributes;  // Custom session attributes
    private Instant createdAt;
    private Instant lastAccessedAt;
}

// Spring Session automatically handles serialization/caching
// No custom code needed
```

**TTL: 30 minutes** (HTTP session timeout).

---

### Layer 3: Serialization Format

#### JSON Serialization with Jackson

```java
@Configuration
public class RedisConfiguration {
    
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        
        // JSON serializer
        Jackson2JsonRedisSerializer<Object> jsonSerializer = 
            new Jackson2JsonRedisSerializer<>(Object.class);
        
        ObjectMapper mapper = new ObjectMapper();
        mapper.enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL);
        jsonSerializer.setObjectMapper(mapper);
        
        // String serializer for keys
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        
        // Key-value serialization
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(jsonSerializer);
        
        return template;
    }
}
```

**Why JSON?**
- Human-readable (debugging in Redis CLI)
- Language-agnostic (can read from other services)
- Supports schema evolution (add fields without breaking old cached values)

**Alternatives:**
- Protocol Buffers (compact, fast, but not human-readable)
- MessagePack (efficient, but less standard)
- Java Serialization (not recommended, security risks)

---

### Layer 4: TTL Strategy

#### TTL by Entity Type

| Entity | TTL | Reason |
|--------|-----|--------|
| Plan | 24 hours | Rarely change, safe to cache long |
| Subscription | 10 minutes | Status changes (upgrade, downgrade), frequent |
| Invoice | 5 minutes | Immutable after creation, short cache safe |
| User | 1 hour | Profile info, moderate changes |
| Tenant Config | 24 hours | Admin config, rarely changes |
| Session | 30 minutes | HTTP session timeout |
| Rate Limit Token | 1 minute | Sliding window, needs frequent updates |
| Notification Queue | No TTL | Manual invalidation after processing |

**Implementation:**

```java
@Configuration
public class CacheTTLConfiguration {
    
    public static class CacheTTL {
        public static final Duration PLAN_TTL = Duration.ofHours(24);
        public static final Duration SUBSCRIPTION_TTL = Duration.ofMinutes(10);
        public static final Duration INVOICE_TTL = Duration.ofMinutes(5);
        public static final Duration USER_TTL = Duration.ofHours(1);
        public static final Duration CONFIG_TTL = Duration.ofHours(24);
        public static final Duration SESSION_TTL = Duration.ofMinutes(30);
    }
}

@Service
public class PlanService {
    
    public void cachePlan(CachedPlan plan) {
        redisTemplate.opsForValue().set(
            "plan:" + plan.getId(),
            plan,
            CacheTTL.PLAN_TTL);  // 24 hour TTL
    }
    
    public void cacheSubscription(CachedSubscription sub) {
        redisTemplate.opsForValue().set(
            "subscription:" + sub.getId(),
            sub,
            CacheTTL.SUBSCRIPTION_TTL);  // 10 minute TTL
    }
}
```

---

### Layer 5: Eviction Policies

When Redis memory fills, evict data according to policy.

**Configuration:**

```yaml
# redis.conf
maxmemory 1gb
maxmemory-policy allkeys-lru  # Evict LRU keys when full

# Options:
# noeviction       - Return error, don't evict
# allkeys-lru      - Evict least-recently-used keys (RECOMMENDED)
# allkeys-lfu      - Evict least-frequently-used keys
# allkeys-random   - Randomly evict keys
# volatile-lru     - Evict LRU keys with TTL
# volatile-random  - Randomly evict keys with TTL
```

**MTBS Recommendation: `allkeys-lru`**

```
Benefit: Automatically frees memory for most-accessed keys
Drawback: Cold data (rarely accessed) evicted first
Acceptable: Cache is optional layer (app always falls back to DB)
```

**Monitoring Evictions:**

```java
@Component
public class RedisEvictionMonitoring {
    
    @Scheduled(fixedRate = 60000)  // Every 1 minute
    public void monitorEvictions() {
        String info = redisTemplate.execute(connection -> {
            return connection.info("stats").get("evicted_keys");
        });
        
        Long evictedKeys = Long.parseLong(info);
        meterRegistry.gauge("redis.evicted_keys_total", evictedKeys);
        
        if (evictedKeys > 1000) {  // More than 1000 evictions/min
            log.warn("High eviction rate: {} keys evicted", evictedKeys);
            alerting.alert("Redis memory pressure: high evictions");
        }
    }
}
```

---

### Layer 6: Cache Invalidation Patterns

#### Pattern 1: TTL-Based (Passive)

```java
// TTL expires automatically
redisTemplate.opsForValue().set(
    "plan:1",
    planObject,
    Duration.ofHours(24));  // Auto-expired after 24 hours

// Pro: Simple, no coordination needed
// Con: Stale data served until TTL expires
```

#### Pattern 2: Explicit Invalidation (Active)

```java
@Service
public class PlanService {
    
    public void updatePlan(String planId, UpdatePlanRequest req) {
        // Update DB
        Plan updated = planRepository.update(planId, req);
        
        // Invalidate cache immediately
        redisTemplate.delete("plan:" + planId);
        
        log.info("Plan cache invalidated: {}", planId);
    }
}
```

**Pro:** Immediate consistency
**Con:** Requires coordination; if app crashes before invalidation, stale cache remains

#### Pattern 3: Event-Based Invalidation (Async)

```java
@Service
public class PlanService {
    
    public void updatePlan(String planId, UpdatePlanRequest req) {
        Plan updated = planRepository.update(planId, req);
        
        // Publish cache invalidation event
        eventPublisher.publishEvent(new CacheInvalidationEvent(
            "plan:" + planId,
            CacheInvalidationType.INVALIDATE
        ));
    }
}

@Component
public class CacheInvalidationListener {
    
    @EventListener
    public void onCacheInvalidation(CacheInvalidationEvent event) {
        if (event.getType() == CacheInvalidationType.INVALIDATE) {
            redisTemplate.delete(event.getKey());
            log.info("Cache invalidated: {}", event.getKey());
        }
    }
}
```

**Pro:** Decoupled; easy to handle in all services
**Con:** Async, slight delay before invalidation

#### Pattern 4: Cache-Aside (Manual Invalidation)

```java
@Service
public class SubscriptionService {
    
    public SubscriptionDTO getSubscription(String subscriptionId) {
        String cacheKey = "subscription:" + subscriptionId;
        
        // Try cache
        CachedSubscription cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached.toDTO();  // Cache hit
        }
        
        // Cache miss: load from DB
        Subscription sub = subscriptionRepository.findById(subscriptionId)
            .orElseThrow();
        
        CachedSubscription cached = new CachedSubscription(sub);
        
        // Store in cache
        redisTemplate.opsForValue().set(
            cacheKey,
            cached,
            Duration.ofMinutes(10));
        
        return cached.toDTO();
    }
    
    public void upgradeSubscription(String subscriptionId, String planId) {
        // Update DB
        Subscription upgraded = subscriptionService.upgrade(subscriptionId, planId);
        
        // Invalidate cache: subscription changed
        redisTemplate.delete("subscription:" + subscriptionId);
        
        // Cascade: invalidate related queries
        redisTemplate.delete("subscription:" + subscriptionId + ":invoices");
    }
}
```

---

## Cache Coherency Across Instances

### Problem: Out-of-Sync Caches

```
Server 1 caches: plan:1 → {price: 299}
Server 2 caches: plan:1 → {price: 299}

Admin updates plan: price → 399
Server 1 invalidates: DELETE plan:1
Server 2 still has: plan:1 → {price: 299} (stale!)
```

### Solution: Pub/Sub Invalidation

```java
@Component
public class CacheInvalidationPublisher {
    
    private final StringRedisTemplate redisTemplate;
    
    public void publishInvalidation(String cacheKey) {
        // Publish to all servers
        redisTemplate.convertAndSend(
            "cache-invalidation-channel",
            cacheKey);
    }
}

@Component
public class CacheInvalidationSubscriber {
    
    @PostConstruct
    public void subscribe() {
        StringRedisTemplate template = redisTemplate;
        
        template.getConnectionFactory()
            .getConnection()
            .subscribe((channel, message) -> {
                if (channel.equals("cache-invalidation-channel")) {
                    String cacheKey = message.toString();
                    
                    // Invalidate on this server
                    redisTemplate.delete(cacheKey);
                    
                    log.info("Cache invalidated (via pub/sub): {}", cacheKey);
                }
            });
    }
}
```

**Flow:**
```
Server 1 updates plan in DB
  → Publishes "plan:1" to Redis pub/sub channel
  
All servers (1, 2, 3) receive message
  → All delete "plan:1" from their local caches
  
Next read: all servers cache miss, reload from DB
  → All get consistent new value
```

---

## Monitoring & Observability

### Cache Metrics

```java
@Component
public class CacheMetricsCollector {
    
    private final MeterRegistry meterRegistry;
    
    @Scheduled(fixedRate = 60000)  // Every 1 minute
    public void collectMetrics() {
        // Get Redis info
        String info = redisTemplate.execute(connection ->
            connection.info("stats")
        );
        
        Long hits = Long.parseLong(extractValue(info, "keyspace_hits"));
        Long misses = Long.parseLong(extractValue(info, "keyspace_misses"));
        Long usedMemory = Long.parseLong(extractValue(info, "used_memory"));
        
        meterRegistry.gauge("redis.hits_total", hits);
        meterRegistry.gauge("redis.misses_total", misses);
        meterRegistry.gauge("redis.memory_bytes", usedMemory);
        
        double hitRate = (double) hits / (hits + misses);
        meterRegistry.gauge("redis.hit_rate", hitRate);
        
        log.info("Cache metrics: hits={}, misses={}, hit_rate={:.2%}, memory={}MB",
            hits, misses, hitRate, usedMemory / 1024 / 1024);
    }
}
```

**Alert Thresholds:**

```yaml
alerts:
  redis:
    hit-rate-low:
      threshold: 0.7  # < 70% hit rate is concerning
      duration: 5m
    memory-high:
      threshold: 0.9  # > 90% memory full
      duration: 2m
    connection-drop:
      threshold: 1
      duration: 1m
```

---

## Known Issues / Limitations

1. **Cache Stampede** — If popular key expires, all requests miss cache simultaneously, overwhelming DB. Solution: Use cache-warming or "soft" TTL + refresh-ahead logic.

2. **Inconsistent Serialization** — If Jackson configuration changes, can't deserialize old cached values. Solution: Version your cache keys or set short TTLs.

3. **Memory Not Freed Immediately** — Even with `allkeys-lru`, doesn't evict immediately when over limit. Peak memory briefly exceeds configured max. Solution: Set maxmemory 10-20% below physical RAM.

4. **Redis Single Point of Failure** — If Redis down, app falls back to DB (slower) but doesn't crash. However, distributed locks fail. Solution: Redis Cluster or Sentinel for HA.

5. **Cascading Cache Invalidation** — Invalidating one key may require invalidating many related keys. Complex dependencies. Solution: Use event-based invalidation, carefully design key hierarchies.

---

## Future Improvements

1. **Cache Warming** — Pre-load frequently-accessed data (plans, configs) into Redis on startup.

2. **Predictive Invalidation** — Use ML to predict which keys will be invalidated soon, refresh proactively.

3. **Compression** — Compress large cached objects to save memory (e.g., gzip).

4. **Redis Streams** — Use streams instead of pub/sub for guaranteed message delivery (prevents missed invalidations).

5. **Cache Analytics** — Track which keys are accessed most, optimize TTL based on actual usage patterns.

---

## Related Documents

- [scalability.md](./scalability.md) — Distributed caching for scale
- [performance.md](./performance.md) — Cache performance impact
- [configuration-reference.md](./configuration-reference.md) — Redis configuration
