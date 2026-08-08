---
version: 1.0
date: 2026-05-17
author: Manoj Pandi
status: Production Ready
tags:
  - scalability
  - horizontal-scaling
  - load-balancing
  - stateless-services
  - database-scaling
  - caching
  - session-management
  - multi-tenancy
  - rate-limiting
related_documents:
  - ./subscription-lifecycle.md
  - ./observability.md
  - ./payment-processing.md
  - ./redis-data-model.md
  - ../ENTERPRISE_REFACTORING_SUMMARY.md
---

# Scalability: Horizontal Scaling, Load Balancing, & Multi-Tenancy

## Executive Summary

**Scalability** is the system's ability to handle increased load by adding more resources. MTBS is architected for **horizontal scaling** — spinning up more application servers behind a load balancer to serve more tenants and requests.

This document explains:
1. **Stateless Application Design** — why no in-memory state, how JWT enables scale-out
2. **Load Balancing Strategy** — request distribution, sticky sessions for WebSocket (future)
3. **Database Scaling** — schema-per-tenant isolation, read replicas, connection pooling
4. **Caching Layer** — Redis for distributed cache invalidation
5. **Rate Limiting** — per-tenant throttling to prevent abuse
6. **Scheduler Scaling** — Quartz job distribution across instances

Key insight: MTBS supports 1000s of tenants across multiple application instances without architectural changes.

---

## Context / Problem

### Historical Problem: Monolith Bottleneck

**Before horizontal scaling:**
```
Peak hour: 10,000 requests/sec, 20 concurrent users per tenant (50 tenants)
Single server: 16-core CPU, 64GB RAM
Capacity: ~8,000 req/sec (80% utilization)

Result: Overload → 503 errors → customer calls → on-call engineer pages
Cost: 2 engineers on-call 24/7 for spike management
Recovery: Manual scale-up (30 min provisioning, restart services)
```

**After horizontal scaling:**
```
Same traffic: 10,000 req/sec across 3 application servers + load balancer
Each server: 8,000 req/sec capacity → total 24,000 req/sec available
Utilization: 42% (headroom for spikes)

Result: No overload, no 503 errors, no on-call needed
Cost: Infrastructure auto-scales, no manual intervention
Recovery: Auto-scaled in 2 minutes (Kubernetes HPA)
```

### Design Principles for Horizontal Scale

**1. Stateless Services** — No in-memory session data. Each request can hit any server.
**2. Shared Storage** — Database and Redis are shared, accessed by all servers.
**3. Idempotent Endpoints** — Retries should not cause double-effects (payment captured twice, invoice created twice).
**4. Distributed Locking** — For critical sections (subscription upgrade, payment capture), use DB locks or Redis locks.
**5. Graceful Shutdown** — Drain in-flight requests before terminating a server, prevent mid-flight failures.

---

## Dependencies

### Inbound (What benefits from scalability)
- **Frontend** — Load balancer distributes requests across app servers
- **Mobile Client** — Same, transparent to client code
- **Webhook Handlers** — Razorpay webhooks routed by load balancer

### Outbound (What infrastructure scalability depends on)
- **Load Balancer** — Nginx, AWS ALB, or Kubernetes Service distributes requests
- **Database** — PostgreSQL primary + read replicas, connection pooling
- **Redis** — Shared cache layer for distributed locks, session data
- **Kubernetes** — (Optional) auto-scaling, service discovery, rolling deployments

### Configuration
```yaml
server:
  port: 8080
  servlet:
    context-path: /api

spring:
  datasource:
    hikari:
      maximum-pool-size: 20  # Connections per app instance
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
  
  redis:
    host: redis-cluster.internal
    port: 6379
    timeout: 2000
    jedis:
      pool:
        max-active: 20
        max-idle: 10
  
  session:
    store-type: redis
    timeout: 1800s  # 30 min session timeout

management:
  endpoint:
    health:
      show-details: always
  health:
    livenessState:
      enabled: true
    readinessState:
      enabled: true
```

---

## Design / Implementation

### Layer 1: Stateless Application Services

#### No In-Memory Session State

**Bad Pattern (not scalable):**
```java
// Map stored in-memory on server instance 1
private static Map<String, UserSession> sessions = new ConcurrentHashMap<>();

@PostMapping("/login")
public LoginResponse login(LoginRequest request) {
    UserSession session = new UserSession(user, roles);
    String sessionId = UUID.randomUUID().toString();
    sessions.put(sessionId, session);  // ONLY in server 1's memory
    
    response.addCookie(new Cookie("sessionId", sessionId));
    return new LoginResponse(sessionId);
}

// Problem: Next request hits server 2, sessionId not found (no shared memory)
```

**Good Pattern (stateless, scalable):**
```java
// No session storage in app. Use JWT (self-contained token).

@PostMapping("/login")
public LoginResponse login(LoginRequest request) {
    User user = userService.authenticate(request.email, request.password);
    
    // JWT contains: userId, roles, tenantId, expiresAt (all claims)
    String jwtToken = jwtService.generateToken(user);
    
    return new LoginResponse(jwtToken);
}

// All subsequent requests carry JWT token
@GetMapping("/subscriptions")
public List<SubscriptionResponse> getSubscriptions(
    @RequestHeader("Authorization") String token) {
    // Verify & decode JWT (stateless)
    Claims claims = jwtService.verifyToken(token);
    String userId = claims.getSubject();
    String tenantId = claims.get("tenantId", String.class);
    
    return subscriptionService.getSubscriptionsForTenant(tenantId);
}

// Works on any server (no session lookup needed)
```

**Consequence:** 
- Database queries increase (verify JWT on every request vs lookup session once)
- Caching mitigates (cache JWT verification result for 5 seconds)
- Trade-off: Scalability wins

#### No Distributed Caching of Credentials

**Issue:** If user password changed, when does JWT become invalid?

**Solution:** Token expiry + refresh token rotation.

```
Access Token: 15-minute expiry (short-lived)
  → User makes requests, token verified fresh each time
  → If password changed, after 15 min new token must be obtained
  
Refresh Token: 7-day expiry (long-lived)
  → Used to obtain new access token without re-entering password
  → If password changed, refresh token revoked
  → User must re-login
```

**Implementation:**
```java
@PostMapping("/auth/refresh")
public TokenResponse refreshToken(
    @RequestHeader("Authorization") String refreshToken) {
    
    Claims claims = jwtService.verifyToken(refreshToken);
    String userId = claims.getSubject();
    
    // Check if refresh token was revoked (password changed, logout, etc.)
    if (tokenRevocationService.isRevoked(refreshToken)) {
        throw new UnauthorizedException("Token revoked");
    }
    
    // Issue new access token
    User user = userService.getUser(userId);
    String newAccessToken = jwtService.generateAccessToken(user);
    
    return new TokenResponse(newAccessToken);
}
```

### Layer 2: Load Balancing

#### Request Distribution Strategy

**Scenario: 3 application servers, 1000 requests/sec**

```
Load Balancer (Nginx)
├─ Server 1 (app:8080) — 333 req/sec
├─ Server 2 (app:8080) — 333 req/sec
└─ Server 3 (app:8080) — 334 req/sec

Distribution Method: Round-Robin (each request alternates)
```

**Nginx Configuration:**
```nginx
upstream backend {
    server app1:8080 weight=1;
    server app2:8080 weight=1;
    server app3:8080 weight=1;
}

server {
    listen 80;
    location /api/ {
        proxy_pass http://backend;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Correlation-ID $http_x_correlation_id;
        
        # Preserve tenant context
        proxy_set_header X-Tenant-ID $http_x_tenant_id;
    }
}
```

#### Sticky Sessions (Not Needed, But Future)

**For WebSocket or polling (future):**
```nginx
# If using sticky sessions (IP-based):
upstream backend {
    ip_hash;  # Routes same client IP to same server
    server app1:8080;
    server app2:8080;
    server app3:8080;
}
```

**Why not needed for HTTP/REST:**
- Stateless requests don't require affinity
- JWT token valid on any server
- Better load distribution without stickiness

#### Health Checks

**Kubernetes (auto-healing):**
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mtbs-app
spec:
  replicas: 3
  template:
    spec:
      containers:
      - name: app
        image: mtbs:v1.0.0
        ports:
        - containerPort: 8080
        
        # Liveness probe: Is server alive?
        livenessProbe:
          httpGet:
            path: /actuator/health/live
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
          timeoutSeconds: 5
        
        # Readiness probe: Can server accept traffic?
        readinessProbe:
          httpGet:
            path: /actuator/health/ready
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
          timeoutSeconds: 3
```

**Probe Responses:**
```json
// GET /actuator/health/live
{
  "status": "UP",
  "components": {
    "diskSpace": {"status": "UP"},
    "db": {"status": "UP"}
  }
}

// Server starting up (not ready yet)
// GET /actuator/health/ready
{
  "status": "DOWN",
  "components": {
    "db": {"status": "DOWN", "detail": "Connecting..."}
  }
}

// Ready to serve traffic
{
  "status": "UP",
  "components": {
    "db": {"status": "UP"},
    "redis": {"status": "UP"}
  }
}
```

### Layer 3: Database Scaling

#### Connection Pooling Per Instance

**HikariCP Configuration (each app server):**
```yaml
spring.datasource.hikari:
  maximum-pool-size: 20
  # 3 servers × 20 = 60 DB connections total
  # PostgreSQL can handle 100 default, increase if needed:
  # postgresql.conf: max_connections = 200
```

**Connection Pool Monitoring:**
```java
@Component
public class DatabaseConnectionMetrics {
    @Bean
    public MeterBinder hikariMetrics(DataSource dataSource) {
        return (registry) -> {
            HikariDataSource hikari = (HikariDataSource) dataSource;
            registry.gauge("db.pool.active", hikari, HikariDataSource::getHikariPoolMXBean);
            registry.gauge("db.pool.idle", hikari, ds -> 
                ds.getMaximumPoolSize() - ds.getHikariPoolMXBean().getActiveConnections());
        };
    }
}
```

#### Schema-Per-Tenant Database Layout

**Multi-tenancy enables independent scaling:**

```
PostgreSQL Instance
├─ public schema
│  ├─ tenants (metadata: t1, t2, t3, ...)
│  ├─ plans (shared plans across tenants)
│  └─ webhook_events (global webhook log)
├─ t1_schema (Tenant 1 private)
│  ├─ subscriptions
│  ├─ invoices
│  └─ payments
├─ t2_schema (Tenant 2 private)
│  ├─ subscriptions
│  ├─ invoices
│  └─ payments
└─ t3_schema (Tenant 3 private)
   └─ ...
```

**Benefit:**
- Tenant A's large queries don't slow Tenant B's queries
- Can scale by migrating tenant to separate DB server
- Backup/restore one tenant without affecting others

**Scaling Path:**
```
Stage 1: All tenants in single DB (up to 100 tenants)
Stage 2: Hot tenants (t1, t2) split to dedicated DB (two servers)
Stage 3: Each tenant in own RDS instance (true isolation)
```

#### Read Replicas for Read-Heavy Workloads

**Setup: Primary + 2 Read Replicas**

```
Write Operations (Subscription, Payment)
  ↓
PostgreSQL Primary (Transactional)
  ├─ Async replication to
  ├─ Read Replica 1
  └─ Read Replica 2

Read Operations (Query subscriptions, invoices)
  ↓ (Routed by application)
  ├─ Replica 1
  ├─ Replica 2
  └─ Primary (fallback if replicas lagging)
```

**Application Routing:**
```java
@Configuration
public class DataSourceConfig {
    
    @Bean
    @Primary
    public DataSource writeDataSource() {
        return DataSourceBuilder.create()
            .driverClassName("org.postgresql.Driver")
            .url("jdbc:postgresql://db-primary.rds.amazonaws.com/mtbs")
            .username("${DB_USER}")
            .password("${DB_PASSWORD}")
            .build();
    }
    
    @Bean
    public DataSource readDataSource() {
        return DataSourceBuilder.create()
            .driverClassName("org.postgresql.Driver")
            .url("jdbc:postgresql://db-replica-1,db-replica-2/mtbs")
            .username("${DB_USER}")
            .password("${DB_PASSWORD}")
            .build();
    }
    
    @Bean
    public RoutingDataSource routingDataSource(
        @Qualifier("writeDataSource") DataSource writeDs,
        @Qualifier("readDataSource") DataSource readDs) {
        
        Map<Object, Object> map = new HashMap<>();
        map.put("write", writeDs);
        map.put("read", readDs);
        
        RoutingDataSource ds = new RoutingDataSource();
        ds.setTargetDataSources(map);
        ds.setDefaultTargetDataSource(writeDs);
        return ds;
    }
}

@Service
public class SubscriptionService {
    
    @Transactional(readOnly = true)
    public SubscriptionResponse getSubscription(String subscriptionId) {
        // @Transactional(readOnly=true) routes to read replica
        return subscriptionRepository.findById(subscriptionId);
    }
    
    @Transactional
    public void upgradePlan(String subscriptionId, String planId) {
        // @Transactional (write) routes to primary
        Subscription sub = subscriptionRepository.findById(subscriptionId);
        sub.setPlanId(planId);
        subscriptionRepository.save(sub);
    }
}
```

**Caveat:** Replica lag (~1 second typical). After upgrade, query to replica might return stale data briefly.

### Layer 4: Caching Layer (Redis)

#### Distributed Cache for Scalability

**Scenario: GET /api/subscriptions called 100x/sec, DB query takes 50ms**

```
Without cache:
100 req/sec × 50ms = 5000ms CPU per second spent on DB queries
Single server: Maxes out core

With Redis cache (10ms lookup):
100 req/sec × 10ms = 1000ms (80% reduction)
Single server: 5x more capacity
```

**Configuration:**
```yaml
spring:
  redis:
    host: redis-cluster.internal
    port: 6379
  cache:
    type: redis
    redis:
      time-to-live: 300000  # 5 min default TTL
```

**Caching Strategy:**
```java
@Service
public class PlanService {
    
    @Cacheable(value = "plans", key = "#planId")
    public Plan getPlan(String planId) {
        // First call: DB query (slow)
        // Subsequent calls within 5 min: Redis lookup (fast)
        return planRepository.findById(planId);
    }
    
    @CacheEvict(value = "plans", key = "#plan.id")
    public void updatePlan(Plan plan) {
        // Admin updates plan, invalidate cache immediately
        planRepository.save(plan);
    }
    
    // Clear entire cache (rare, e.g., system migration)
    @CacheEvict(value = "plans", allEntries = true)
    public void clearPlansCache() {
    }
}
```

**Monitoring Redis Cache:**
```java
@Component
public class CacheMetrics {
    @Bean
    public MeterBinder cacheMetrics(CacheManager cacheManager) {
        return registry -> {
            Cache cache = cacheManager.getCache("plans");
            if (cache instanceof RedisCache) {
                registry.gauge("cache.size", () -> cache.getCache().size());
                registry.gauge("cache.hits", () -> cache.nativeCache().get("hits"));
                registry.gauge("cache.misses", () -> cache.nativeCache().get("misses"));
            }
        };
    }
}
```

### Layer 5: Distributed Locking

#### Preventing Double-Execution in Distributed System

**Scenario: Two servers receive webhook payment.captured simultaneously**

```
Server 1: receives webhook → starts verification
  ↓
Server 2: receives webhook → starts verification
  ↓
Both: verify signature (OK) → call activateUpgrade
  ↓
Result: Subscription upgraded TWICE (plan changed twice)
```

**Solution: Distributed Lock**

```java
@Service
public class PaymentService {
    
    public PaymentVerificationResponse verifyAndCapturePayment(String invoiceId) {
        // Acquire lock: "payment-verify-{invoiceId}"
        String lockKey = "payment-verify-" + invoiceId;
        
        try (RedisLock lock = redisLockService.acquire(lockKey, Duration.ofSeconds(30))) {
            if (!lock.isAcquired()) {
                // Another server is processing this invoice
                return new PaymentVerificationResponse(
                    PaymentStatus.ALREADY_PROCESSING);
            }
            
            // Safe to proceed: only one server executing
            Invoice invoice = invoiceService.getInvoice(invoiceId);
            
            // Check idempotency: was this already captured?
            if (invoice.getStatus() == InvoiceStatus.PAID) {
                return new PaymentVerificationResponse(
                    PaymentStatus.ALREADY_CAPTURED);
            }
            
            // Verify signature
            boolean valid = verifyRazorpaySignature(invoice);
            if (!valid) {
                throw new PaymentException("Signature invalid");
            }
            
            // Mark as paid (only one server doing this)
            invoice.setStatus(InvoiceStatus.PAID);
            invoiceService.save(invoice);
            
            // Activate subscription upgrade
            subscriptionService.activateUpgradeAfterPayment(invoiceId);
            
            return new PaymentVerificationResponse(PaymentStatus.CAPTURED);
            
        } catch (LockAcquisitionException e) {
            log.warn("Could not acquire lock for {}", invoiceId);
            return new PaymentVerificationResponse(
                PaymentStatus.ALREADY_PROCESSING);
        }
    }
}
```

**Redis Lock Implementation:**
```java
@Component
public class RedisLockService {
    private final StringRedisTemplate redisTemplate;
    
    public RedisLock acquire(String key, Duration timeout) {
        String lockId = UUID.randomUUID().toString();
        boolean acquired = Boolean.TRUE.equals(
            redisTemplate.opsForValue().setIfAbsent(
                key, lockId,
                timeout)
        );
        
        if (acquired) {
            return new RedisLock(this, key, lockId, true);
        } else {
            return new RedisLock(this, key, lockId, false);
        }
    }
    
    public void release(String key, String lockId) {
        redisTemplate.delete(key);
    }
}

public class RedisLock implements AutoCloseable {
    private final RedisLockService service;
    private final String key, lockId;
    private final boolean acquired;
    
    public boolean isAcquired() {
        return acquired;
    }
    
    @Override
    public void close() {
        if (acquired) {
            service.release(key, lockId);
        }
    }
}
```

### Layer 6: Scheduler Distribution (Quartz)

#### Ensuring Only One Instance Runs a Scheduled Job

**Problem:** 3 app servers all running Quartz scheduler independently

```
Server 1: runs downgrade-at-period-end job every minute
Server 2: runs downgrade-at-period-end job every minute
Server 3: runs downgrade-at-period-end job every minute

Result: Same downgrade executed 3 times (side effect: 3 invoices, 3 emails)
```

**Solution: Quartz with JDBC Job Store**

```yaml
spring:
  quartz:
    job-store-type: jdbc  # Use DB instead of RAM
    jdbc:
      initialize-schema: never  # schema pre-created by Flyway
    properties:
      org.quartz.jobStore.driverDelegateClass: org.quartz.impl.jdbcjobstore.PostgreSQLDelegate
      org.quartz.jobStore.useProperties: false
      org.quartz.jobStore.tablePrefix: qrtz_  # quartz tables in public schema
      org.quartz.threadPool.threadCount: 4
```

**Result: JDBC Job Store (DB-backed scheduling)**

```
Server 1, 2, 3 all connect to Quartz DB
↓
Next "downgrade-at-period-end" trigger: 14:00
↓
Quartz acquires database lock on qrtz_triggers
Only Server 2 gets lock → executes job
Servers 1 & 3: wait for lock, then skip (job already executed)
↓
Job completes, lock released
↓
Next trigger: 15:00 (might be Server 1 or Server 3, doesn't matter)
```

**No code changes needed:** Quartz handles distribution transparently.

---

## Scaling Examples

### Example 1: Traffic Surge (Holiday Sale)

**Baseline:**
```
3 app servers, 1000 req/sec, 60% CPU utilization
```

**Holiday surge hits: 5000 req/sec (5x peak)**

```
Kubernetes HPA triggers auto-scaling:
Current: 3 servers
Target CPU: 60%
Actual: 85% (overload)
↓
HPA scales to: 3 + (85-60)/10 = ~8 servers

In 2 minutes:
- 8 pods provisioned
- Load balancer routes requests: 625 req/sec per server
- CPU drops to: 65% (healthy)
- No 503 errors
```

**Cost:** Short-lived infrastructure (spun down after surge).

### Example 2: Single Tenant Grown (Uber-Scale Customer)

**Setup:**
```
Tenant: enterprise-corp-inc (t99)
Default behavior: t99_schema shares DB with 400 other tenants
```

**t99 Grows:**
```
t99 subscriptions: 50,000 (vs 100 for typical tenant)
t99 invoice queries: 10,000/sec (vs 50 for typical tenant)
```

**Impact:**
```
t99 queries slow down other tenants (shared DB)
Complaint: "Our invoices page takes 5 seconds to load"
```

**Solution: Move t99 to dedicated DB**

```
Before:
postgres-shared: t1_schema, t2_schema, ..., t99_schema, ..., t1000_schema

After:
postgres-shared: t1_schema, t2_schema, ..., t1000_schema (minus t99)
postgres-enterprise-t99: t99_schema (dedicated, 2x CPU, 100GB SSD)

Result:
t99 invoice queries: 50ms (was 2000ms)
Other tenants: 100ms (was 120ms) — slight improvement
Cost: +$200/month for dedicated DB, but justified for $100k/year customer
```

### Example 3: Scheduled Job Scaling

**Setup:** Downgrade-at-period-end job runs daily

**Load (1000 tenants):**
```
Subscriptions eligible for downgrade: 50
Job time: 50 × 100ms = 5 seconds

3 servers, JDBC job store:
- Server 1 runs downgrade job on Monday at 01:00 UTC
- Server 2 runs downgrade job on Tuesday at 01:00 UTC
- Server 3 runs downgrade job on Wednesday at 01:00 UTC

Each server: 5 seconds CPU per week (negligible)
```

**If job takes too long (e.g., 1 hour):**
```
Solution 1: Add index to subscriptions(status, downgradeAt) — optimize query
Solution 2: Batch into 100 at a time, release lock between batches
Solution 3: Move to dedicated scheduler service (separate process)
```

---

## Anti-Patterns to Avoid

### ❌ Anti-Pattern 1: Shared In-Memory Cache

```java
// BAD: Cache local to server instance
private static Map<String, Subscription> subscriptionCache = new ConcurrentHashMap<>();

public Subscription getSubscription(String id) {
    if (subscriptionCache.containsKey(id)) {
        return subscriptionCache.get(id);  // Server 1 only has its cache
    }
    Subscription sub = database.get(id);
    subscriptionCache.put(id, sub);
    return sub;
}
```

**Problem:**
- Server 1 caches subscription at 14:00
- Server 2 doesn't know about it
- Admin updates subscription at 14:05
- Server 1 still serves stale cache until TTL expires

**Solution:** Use distributed Redis cache.

### ❌ Anti-Pattern 2: Sticky Sessions Without Shared State

```java
// BAD: Session stored in server memory
@PostMapping("/login")
public LoginResponse login(LoginRequest req) {
    HttpSession session = httpRequest.getSession(true);
    session.setAttribute("user", user);  // Only in Server 1's memory
    return new LoginResponse(session.getId());
}

// Next request might hit Server 2
@GetMapping("/profile")
public ProfileResponse getProfile(HttpSession session) {
    User user = (User) session.getAttribute("user");  // NULL on Server 2!
}
```

**Solution:** Use JWT or Redis session store.

### ❌ Anti-Pattern 3: Non-Idempotent Operations

```java
// BAD: Double-click payment button
POST /api/payments/verify
Server 1: captures payment → subscriber upgraded
Retry (due to network timeout): Server 1 captures payment AGAIN
Result: User charged twice
```

**Solution:** Idempotency key.

```java
// GOOD: Idempotency key
@PostMapping("/payments/verify")
public PaymentResponse verify(
    @RequestHeader("Idempotency-Key") String idempotencyKey,
    @RequestBody PaymentRequest req) {
    
    // Check if already processed
    PaymentResponse existing = idempotencyKeyService.get(idempotencyKey);
    if (existing != null) {
        return existing;  // Return cached result
    }
    
    // Process payment (first time)
    PaymentResponse response = processPayment(req);
    
    // Cache result
    idempotencyKeyService.store(idempotencyKey, response, Duration.ofHours(24));
    
    return response;
}
```

---

## Known Issues / Limitations

1. **Database Connection Pool Tuning** — Optimal pool size = CPU cores + 1. Too high = memory waste. Too low = connection starvation under spike. Requires load testing.

2. **Replica Lag** — Read replicas lag behind primary by ~1 second. After write, queries to replica might see stale data. Applications must handle.

3. **Distributed Lock Timeout** — If server crashes while holding lock (e.g., `Server 1: acquire lock → crash → lock never released`), lock times out after 30s. Other servers wait idle. Trade-off: shorter timeout risks race condition, longer timeout causes cascading delays.

4. **Redis Single Point of Failure** — If Redis goes down, all distributed locks fail. Cache hits fail (but fall back to DB). Solution: Redis Cluster or Sentinel (high availability), but adds complexity.

5. **Stateless Doesn't Mean Scalable** — Still must solve: database bottleneck, cache coherency, lock efficiency. Horizontal scaling is necessary but not sufficient.

6. **Scheduler Scaling is "Lucky"** — With JDBC job store, only one server runs each job. Which server? Non-deterministic (whichever acquires DB lock first). If needed guaranteed server: use dedicated scheduler service.

---

## Future Improvements

1. **Microservices Architecture** — Separate Auth, Billing, Notification services. Each scalable independently. Enables true microservices scaling.

2. **Message Queue (RabbitMQ, Kafka)** — Decouple payment processing from subscription upgrade. If upgrade service is slow, payment service doesn't block. Both scale independently.

3. **Dedicated Scheduler Service** — Instead of Quartz in app servers, use separate service (e.g., Temporal, AirByte). Easier to scale, upgrade, monitor.

4. **Database Sharding** — If Postgres maxes out, shard by tenantId. t1-t100 on shard 1, t101-t200 on shard 2. More complex (shard key in every query), but extreme scale.

5. **CDN for Static Assets** — Cache frontend assets (JS, CSS, images) on CloudFront/Cloudflare. Reduce app server load.

6. **Caching Strategy Optimization** — Use cache-aside, write-through, or write-behind depending on use case. Implement cache warming for frequently-accessed data.

7. **Rate Limiting per Tenant** — Prevent noisy neighbors. Tenant X making 100k requests/day shouldn't slow Tenant Y. Implement token bucket algorithm.

---

## Related Documents

- [redis-data-model.md](./redis-data-model.md) — Distributed cache structure
- [scheduler-jobs.md](../06-scheduler/scheduler-jobs.md) — Quartz job distribution
- [observability.md](./observability.md) — Metrics for load and scaling
- [payment-processing.md](./payment-processing.md) — Idempotency in payments
