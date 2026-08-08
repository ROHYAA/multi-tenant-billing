---
version: 1.0
date: 2026-05-17
author: Manoj Pandi
status: Production Ready
tags:
  - configuration
  - properties
  - environment-variables
  - profiles
  - secrets-management
  - application-yaml
  - deployment
related_documents:
  - ../ENTERPRISE_REFACTORING_SUMMARY.md
  - ./observability.md
  - ./scalability.md
  - ./payment-processing.md
---

# Configuration Reference: Properties, Environment Variables, & Profiles

## Executive Summary

MTBS uses **Spring Boot profiles** (dev, test, prod) with environment-specific `application-{profile}.yaml` files. All sensitive values (API keys, DB passwords, JWT secrets) are injected via environment variables, never committed to Git.

This document serves as a comprehensive reference for all configurable properties, their purposes, recommended values, and how to override them.

---

## Configuration Hierarchy

Spring Boot resolves configuration in this order (later overrides earlier):

```
1. application.yaml (default)
2. application-{profile}.yaml (dev/test/prod)
3. Environment variables (highest priority)
4. System properties (-D flags)
```

**Example: Database URL**

```yaml
# application.yaml
spring.datasource.url: jdbc:postgresql://localhost:5432/mtbs

# application-prod.yaml (overrides)
spring.datasource.url: jdbc:postgresql://prod-db.rds.amazonaws.com:5432/mtbs

# Environment variable (highest priority)
export SPRING_DATASOURCE_URL=jdbc:postgresql://prod-db-replica.rds.amazonaws.com/mtbs
```

---

## Core Application Configuration

### Server Settings

```yaml
server:
  port: 8080  # HTTP port
  servlet:
    context-path: /api  # Base path for all endpoints
    encoding:
      charset: UTF-8
      force: true
  
  compression:
    enabled: true
    min-response-size: 1024  # Compress responses >1KB
    mime-types: text/html,application/json,text/css
  
  shutdown: graceful  # Wait for requests to complete
  tomcat:
    threads:
      max: 200  # Max request processing threads
      min-spare: 10
    connection-timeout: 20000ms
    accept-count: 100  # Queue size when threads maxed
```

**Environment Override:**
```bash
export SERVER_PORT=9090
export SERVER_SERVLET_CONTEXT_PATH=/v1/api
```

### Spring Framework Core

```yaml
spring:
  application:
    name: mtbs  # Application name for logs
  
  profiles:
    active: dev  # Active profile (dev/test/prod)
  
  jackson:
    serialization:
      write-dates-as-timestamps: false  # ISO 8601 format
      fail-on-empty-beans: false
    deserialization:
      fail-on-unknown-properties: false  # Ignore extra JSON fields
  
  lifecycle:
    timeout-per-shutdown-phase: 30s  # Graceful shutdown timeout
```

---

## Database Configuration

### Primary Database (PostgreSQL)

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/mtbs
    username: ${DB_USER}  # From environment
    password: ${DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
    
    hikari:
      maximum-pool-size: 20  # Max connections per app instance
      minimum-idle: 5  # Min idle connections
      connection-timeout: 30000  # 30s wait for connection
      idle-timeout: 600000  # 10 min before idle connection closed
      max-lifetime: 1800000  # 30 min max connection lifetime
      auto-commit: true
      leak-detection-threshold: 60000  # Warn if connection held >60s
  
  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect
    hibernate:
      ddl-auto: validate  # validate/update/create/create-drop
    properties:
      hibernate:
        show_sql: false  # Enable in dev only
        format_sql: false  # Enable in dev only
        jdbc:
          batch_size: 20
          fetch_size: 50
        default_batch_fetch_size: 25  # Batch loading N+1 prevention
  
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration
    schemas: public  # Schema to migrate
```

**Development Override (application-dev.yaml):**
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/mtbs_dev
    username: postgres
    password: postgres
  
  jpa:
    hibernate:
      ddl-auto: update  # Auto-create/update schema
    properties:
      hibernate:
        show_sql: true  # Log SQL in dev
        format_sql: true
```

**Production Override (application-prod.yaml):**
```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 50  # Higher in prod
      leak-detection-threshold: 180000
  
  jpa:
    hibernate:
      ddl-auto: validate  # Never auto-modify prod schema
    properties:
      hibernate:
        jdbc:
          batch_size: 50  # Batch larger in prod
```

**Environment Variables (Production):**
```bash
export DB_HOST=prod-db.rds.amazonaws.com
export DB_PORT=5432
export DB_NAME=mtbs
export DB_USER=${SECRETS_MANAGER_DB_USER}
export DB_PASSWORD=${SECRETS_MANAGER_DB_PASSWORD}
```

---

## Redis Configuration

### Cache & Session Store

```yaml
spring:
  redis:
    host: localhost
    port: 6379
    password: ${REDIS_PASSWORD}  # Empty if no auth
    timeout: 2000  # Connection timeout
    database: 0
    
    ssl: false  # Enable in prod
    
    jedis:
      pool:
        max-active: 20  # Max active connections
        max-idle: 10
        min-idle: 2
        max-wait: Duration: 2000
    
    # For Redis Cluster
    # cluster:
    #   nodes:
    #     - cluster-node-1:6379
    #     - cluster-node-2:6379
  
  cache:
    type: redis
    redis:
      time-to-live: 300000  # 5 min default cache TTL
  
  session:
    store-type: redis
    timeout: 1800s  # 30 min session timeout
    redis:
      namespace: mtbs:session
      flush-mode: on-save
```

**Production Override:**
```yaml
spring:
  redis:
    host: redis-cluster.internal
    port: 6379
    password: ${REDIS_PASSWORD}
    ssl: true
    jedis:
      pool:
        max-active: 50  # Higher in prod
```

---

## JPA & Hibernate Configuration

### Entity Scanning & Lazy Loading

```yaml
spring:
  jpa:
    hibernate:
      naming:
        physical-strategy: org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl
    show-sql: false
    properties:
      hibernate:
        enable_lazy_load_no_trans: false  # Prevent lazy loading outside transaction
        dialect: org.hibernate.dialect.PostgreSQL10Dialect
        format_sql: false
        jdbc:
          batch_size: 20
          batch_versioned_data: true
          fetch_size: 50
        default_batch_fetch_size: 25
        use_sql_comments: true  # Add /* HQL comments */ in SQL
```

---

## Authentication & JWT

### JWT Token Configuration

```yaml
app:
  jwt:
    secret: ${JWT_SECRET}  # Min 256 bits for HS256
    access-token-expiry: 15m  # Access token: 15 minutes
    refresh-token-expiry: 7d  # Refresh token: 7 days
    issuer: https://mtbs.example.com
    audience: mtbs-app
    
  security:
    cors:
      allowed-origins:
        - http://localhost:3000  # Dev frontend
        - https://app.example.com  # Prod frontend
      allowed-methods: GET,POST,PUT,DELETE,OPTIONS
      allowed-headers: "*"
      expose-headers: X-Correlation-ID,X-Total-Count
      allow-credentials: true
      max-age: 3600
```

**Environment Override:**
```bash
export JWT_SECRET=$(openssl rand -base64 32)  # Generate random secret
# Output: 8+h6K9L2M3n4O5P6Q7R8S9T0U1V2W3X4Y5Z=
```

---

## Payment Gateway (Razorpay)

### API & Webhook Configuration

```yaml
razorpay:
  enabled: true
  key-id: ${RAZORPAY_KEY_ID}  # Public key
  key-secret: ${RAZORPAY_KEY_SECRET}  # Secret key
  webhook-secret: ${RAZORPAY_WEBHOOK_SECRET}  # Webhook HMAC secret
  api-base-url: https://api.razorpay.com
  
  # Business rules
  currency: INR
  minimum-amount: 1.0  # Minimum ₹1
  maximum-amount: 999999.99  # Maximum ₹999,999.99
  receipt-prefix: MTBS-
  
  # Timeout settings
  connect-timeout: 10000  # 10s
  read-timeout: 30000  # 30s
  
  # Retry policy
  max-retries: 3
  retry-backoff-ms: 1000
```

**Environment Override (Production):**
```bash
export RAZORPAY_KEY_ID=${SECRETS_MANAGER_RAZORPAY_KEY_ID}
export RAZORPAY_KEY_SECRET=${SECRETS_MANAGER_RAZORPAY_KEY_SECRET}
export RAZORPAY_WEBHOOK_SECRET=${SECRETS_MANAGER_RAZORPAY_WEBHOOK_SECRET}
```

**Webhook Endpoint Configuration:**
```yaml
app:
  webhooks:
    razorpay:
      enabled: true
      path: /webhooks/razorpay
      ip-whitelist:
        - 13.126.86.0/24  # Razorpay IP range (example)
        - 13.127.232.0/21
      signature-algorithm: SHA256
      allow-ip-verification: true  # Verify source IP
```

---

## Email Configuration

### SMTP Settings for Notifications

```yaml
mail:
  smtp:
    host: ${SMTP_HOST}  # smtp.gmail.com for Gmail
    port: ${SMTP_PORT}  # 587 for TLS, 465 for SSL
    username: ${SMTP_USERNAME}
    password: ${SMTP_PASSWORD}
    
    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true  # Enable STARTTLS
            required: true
          connectiontimeout: 10000
          timeout: 30000
          writetimeout: 30000
        from: noreply@example.com
        from-name: MTBS Billing
  
  # Thread pool for async email sending
  executor:
    thread-pool-size: 10
    queue-capacity: 1000
    keep-alive-seconds: 300
```

**Environment Override (Gmail):**
```bash
export SMTP_HOST=smtp.gmail.com
export SMTP_PORT=587
export SMTP_USERNAME=your-email@gmail.com
export SMTP_PASSWORD=app-specific-password  # Not regular password
```

**Environment Override (AWS SES):**
```bash
export SMTP_HOST=email-smtp.us-east-1.amazonaws.com
export SMTP_PORT=587
export SMTP_USERNAME=${SES_SMTP_USERNAME}
export SMTP_PASSWORD=${SES_SMTP_PASSWORD}
```

---

## Quartz Scheduler

### Job Configuration

```yaml
spring:
  quartz:
    job-store-type: jdbc  # Use database instead of RAM
    wait-for-jobs-to-complete-on-shutdown: true
    overwrite-existing-jobs: true
    
    properties:
      org.quartz.scheduler.instanceName: MTBS
      org.quartz.scheduler.instanceId: AUTO  # Auto-assign per server
      org.quartz.jobStore.class: org.quartz.impl.jdbcjobstore.JobStoreTX
      org.quartz.jobStore.driverDelegateClass: org.quartz.impl.jdbcjobstore.PostgreSQLDelegate
      org.quartz.jobStore.dataSource: quartz
      org.quartz.jobStore.tablePrefix: qrtz_
      org.quartz.jobStore.useProperties: false
      org.quartz.jobStore.misfireThreshold: 60000  # 1 min threshold
      org.quartz.threadPool.class: org.quartz.simpl.SimpleThreadPool
      org.quartz.threadPool.threadCount: 10
      org.quartz.threadPool.threadPriority: 5
      org.quartz.plugin.shutdownhook.class: org.quartz.plugins.management.ShutdownHookPlugin
      org.quartz.plugin.shutdownhook.cleanShutdown: true

app:
  jobs:
    downgrade-at-period-end:
      enabled: true
      cron: "0 0 */1 * * ?"  # Every hour
      batch-size: 100
    
    send-invoice-reminders:
      enabled: true
      cron: "0 0 9 * * ?"  # 9 AM daily
      look-ahead-days: 7
    
    update-subscription-usage:
      enabled: true
      cron: "0 */5 * * * ?"  # Every 5 minutes
      batch-size: 500
```

---

## Logging Configuration

### SLF4J + Logback

```yaml
logging:
  level:
    root: WARN  # Default level
    com.mtbs: INFO
    com.mtbs.billing: DEBUG  # Sensitive module
    org.springframework.web: WARN
    org.springframework.security: INFO
    org.hibernate: WARN
  
  pattern:
    console: "%d{ISO8601} %highlight(%-5level) [%X{tenantId}] [%X{correlationId}] %logger{36} : %msg%n"
    file: "%d{ISO8601} %-5level [%X{tenantId}] [%X{correlationId}] %logger{36} : %msg%n"
  
  file:
    name: logs/mtbs.log
    max-size: 100MB
    max-history: 30  # Keep 30 days of logs
    total-size-cap: 10GB  # Total log size before cleanup
```

**JSON Logging (Production):**

```yaml
# application-prod.yaml
logging:
  config: classpath:logback-spring.xml  # Custom XML config

# logback-spring.xml
<configuration>
  <appender name="json-file" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/mtbs.json</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
      <fileNamePattern>logs/mtbs-%d{yyyy-MM-dd}.%i.json.gz</fileNamePattern>
      <maxFileSize>100MB</maxFileSize>
      <maxHistory>30</maxHistory>
    </rollingPolicy>
    <encoder class="net.logstash.logback.encoder.LogstashEncoder" />
  </appender>
  
  <root level="INFO">
    <appender-ref ref="json-file" />
  </root>
</configuration>
```

---

## Monitoring & Management Endpoints

### Actuator Configuration

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus,info,configprops
      exclude: env  # Don't expose environment variables
      base-path: /actuator
  
  endpoint:
    health:
      show-details: when-authorized  # Show detailed health only if authenticated
      show-components: when-authorized
      probes:
        enabled: true  # Liveness/readiness probes
    
    shutdown:
      enabled: false  # Don't allow remote shutdown via HTTP
  
  health:
    livenessState:
      enabled: true  # /actuator/health/live
    readinessState:
      enabled: true  # /actuator/health/ready
    
    db:
      enabled: true  # Check database connectivity
    redis:
      enabled: true  # Check Redis connectivity
  
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: mtbs
      environment: ${ENVIRONMENT:dev}
      version: 1.0.0
```

**Health Endpoints:**

```bash
# Basic health check
GET /actuator/health
{
  "status": "UP",
  "components": {
    "db": {"status": "UP"},
    "redis": {"status": "UP"}
  }
}

# Liveness probe (K8s uses this)
GET /actuator/health/live
{
  "status": "UP"
}

# Readiness probe (K8s uses this)
GET /actuator/health/ready
{
  "status": "UP"
}

# Metrics
GET /actuator/prometheus
# (Prometheus format output)
```

---

## Multi-Tenancy Settings

### Tenant Context Configuration

```yaml
app:
  multi-tenancy:
    enabled: true
    strategy: SCHEMA_PER_TENANT  # SCHEMA_PER_TENANT or DATABASE_PER_TENANT
    
    # Header for tenant identification
    tenant-header: X-Tenant-ID
    
    # Or extract from JWT claim
    tenant-jwt-claim: tenant_id
    
    # Default tenant for ops/admin
    default-tenant: admin-tenant
    
    # Tenant schema prefix
    schema-prefix: t  # tenant schema: t1_schema, t2_schema
    
    # Schema resolution
    resolve-on-startup: false  # Don't load all tenant schemas at startup
```

---

## Security Settings

### CORS, CSRF, Rate Limiting

```yaml
app:
  security:
    cors:
      allowed-origins:
        - ${FRONTEND_URL:http://localhost:3000}
      allowed-methods: GET,POST,PUT,DELETE,OPTIONS
      allowed-headers: "*"
      expose-headers: X-Correlation-ID,X-Total-Count
      allow-credentials: true
      max-age: 3600
    
    rate-limiting:
      enabled: true
      requests-per-minute: 1000  # Per IP address
      requests-per-second: 20  # Burst limit
      exempt-paths:
        - /actuator/health
        - /webhooks/razorpay
    
    csrf:
      enabled: false  # Disabled for stateless JWT auth
    
    require-https: true  # Enforce HTTPS in prod
```

---

## Feature Flags

### Conditional Feature Enablement

```yaml
app:
  features:
    notifications:
      enabled: true
      channels:
        email:
          enabled: true
          template-engine: THYMELEAF
        sms:
          enabled: false
    
    payment-processing:
      enabled: true
      razorpay:
        enabled: true
        mock-mode: false  # Use real Razorpay in prod
      
    webhook-validation:
      enabled: true
      require-signature: true
      require-ip-whitelist: true
    
    proration:
      enabled: true
      calculate-credits: true
      apply-razorpay-minimum: true
    
    scheduler-jobs:
      downgrade-at-period-end:
        enabled: true
      send-invoice-reminders:
        enabled: true
      update-usage-metrics:
        enabled: true
```

---

## Environment-Specific Profiles

### Development (application-dev.yaml)

```yaml
spring:
  profiles: dev
  
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
  
  redis:
    host: localhost
    port: 6379

logging:
  level:
    com.mtbs: DEBUG
    org.springframework.web: DEBUG

app:
  jwt:
    secret: dev-secret-key-not-secure
  features:
    payment-processing:
      razorpay:
        mock-mode: true  # Mock Razorpay in dev
```

### Test (application-test.yaml)

```yaml
spring:
  profiles: test
  
  jpa:
    hibernate:
      ddl-auto: create-drop
    database-platform: org.hibernate.dialect.H2Dialect
  
  datasource:
    url: jdbc:h2:mem:testdb
    driver-class-name: org.h2.Driver

  redis:
    host: localhost
    port: 6379

logging:
  level:
    com.mtbs: DEBUG
```

### Production (application-prod.yaml)

```yaml
spring:
  profiles: prod
  
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
  
  redis:
    host: ${REDIS_HOST}
    ssl: true
    password: ${REDIS_PASSWORD}
  
  datasource:
    hikari:
      maximum-pool-size: 50
      leak-detection-threshold: 180000

logging:
  level:
    root: WARN
    com.mtbs: INFO
  file:
    name: /var/log/mtbs/mtbs.log
    max-size: 500MB
    max-history: 90

app:
  security:
    require-https: true
  features:
    payment-processing:
      razorpay:
        mock-mode: false
```

---

## Configuration for Deployment

### Docker Environment

```dockerfile
# Dockerfile
FROM openjdk:17-slim
COPY target/mtbs-app.jar app.jar

# Set active profile
ENV SPRING_PROFILES_ACTIVE=prod

# Expose port
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Docker Compose:**
```yaml
version: '3.8'
services:
  app:
    image: mtbs:latest
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DB_HOST: postgres
      DB_USER: mtbs_user
      DB_PASSWORD: ${DB_PASSWORD}
      REDIS_HOST: redis
      JWT_SECRET: ${JWT_SECRET}
      RAZORPAY_KEY_ID: ${RAZORPAY_KEY_ID}
    ports:
      - "8080:8080"
    depends_on:
      - postgres
      - redis

  postgres:
    image: postgres:15
    environment:
      POSTGRES_USER: mtbs_user
      POSTGRES_PASSWORD: ${DB_PASSWORD}
      POSTGRES_DB: mtbs

  redis:
    image: redis:7-alpine
```

### Kubernetes Deployment

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
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "prod"
        - name: DB_HOST
          valueFrom:
            configMapKeyRef:
              name: mtbs-config
              key: db-host
        - name: DB_PASSWORD
          valueFrom:
            secretKeyRef:
              name: mtbs-secrets
              key: db-password
        - name: JWT_SECRET
          valueFrom:
            secretKeyRef:
              name: mtbs-secrets
              key: jwt-secret
```

---

## Configuration Validation

### At Startup

Spring Boot validates all `@ConfigurationProperties` at startup:

```java
@Configuration
@ConfigurationProperties(prefix = "app.jwt")
@Validated
public class JwtConfig {
    
    @NotBlank(message = "JWT secret must not be blank")
    @Length(min = 32, message = "JWT secret must be at least 32 characters")
    private String secret;
    
    @NotNull(message = "Access token expiry must be set")
    private Duration accessTokenExpiry;
}

// If validation fails at startup, app refuses to start
// Error example: "JWT secret must not be blank"
```

---

## Known Issues / Limitations

1. **Secrets in Git** — Environment variables not in Git (good), but developers sometimes commit passwords to `application-dev.yaml`. Use `.gitignore` to prevent.

2. **Property Name Mapping** — `snake_case` vs `camelCase`. Spring converts both: `razorpay.key-id` = `razorpay.keyId`. Can be confusing.

3. **Profile-Specific Logging** — If you forget `logging.level.com.mtbs: DEBUG` in application-dev.yaml, dev environment logs at INFO level (surprising).

4. **Circular Property References** — `${some.property}` can reference other properties, but cycles cause errors.

---

## Related Documents

- [ENTERPRISE_REFACTORING_SUMMARY.md](../ENTERPRISE_REFACTORING_SUMMARY.md) — Architecture overview
- [observability.md](./observability.md) — Logging and metrics configuration
- [payment-processing.md](./payment-processing.md) — Razorpay integration config
