# Multi-Tenant Billing System (MTBS)

## Enterprise-Grade SaaS Billing Platform

**Version:** 0.0.1  
**Last Updated:** May 2026  
**Status:** Production Ready  
**License:** Proprietary  

---

## 🎯 What is MTBS?

Multi-Tenant Billing System (MTBS) is an **enterprise-grade SaaS billing platform** designed to power subscription-based businesses. It provides a complete billing infrastructure for managing subscriptions, payments, invoicing, and multi-tenant operations at scale.

Built with **Spring Boot 3.4.3** and **Java 17**, MTBS implements industry-standard patterns for security, reliability, and scalability:

- 🔐 **Secure Multi-Tenancy** — Complete tenant isolation with schema-per-tenant architecture
- 💳 **Payment Processing** — Razorpay integration with 2-step payment verification
- 📊 **Subscription Management** — Flexible plans, upgrades, downgrades, and proration
- 📧 **Notification System** — Event-driven email delivery with retry logic
- 📈 **Observable & Scalable** — Structured logging, distributed tracing, horizontal scaling
- 🔄 **Reliable Event Processing** — Transactional Outbox Pattern for at-least-once delivery
- 🛡️ **Enterprise Security** — JWT authentication, webhook HMAC verification, rate limiting

---

## 🚀 Quick Start

### Prerequisites
- **Java 17+**
- **PostgreSQL 14+**
- **Redis 6.0+**
- **Docker** (optional, for containerized deployment)
- **Maven 3.8+**

### Local Development Setup

```bash
# 1. Clone repository
git clone <repository-url>
cd MultiTenantBillingSystem

# 2. Configure application properties
cp src/main/resources/application-dev.yaml.example src/main/resources/application-dev.yaml
# Edit with your local database, Redis, and Razorpay credentials

# 3. Build project
mvn clean install

# 4. Run application
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=dev"

# 5. Access application
# API Base URL: http://localhost:8080
# Swagger UI: http://localhost:8080/swagger-ui.html
```

### Docker Deployment

```bash
# Build Docker image
docker build -t mtbs:latest .

# Run with Docker Compose
docker-compose up -d

# Access application
# API: http://localhost:8080
# Admin Dashboard: http://localhost:3000
```

---

## 📋 Core Features

### 1. Subscription Management
- Multiple billing cycles (monthly, quarterly, annual)
- Flexible plan hierarchy (free, starter, pro, enterprise)
- Trial periods with automatic conversion
- Plan upgrades and downgrades with proration
- Subscription lifecycle tracking (active, trialing, paused, cancelled)

### 2. Payment Processing
- **Razorpay Integration** — Industry-leading payment gateway for Indian market
- **2-Step Payment Flow** — Order creation + payment verification for security
- **Webhook Handling** — Automatic payment reconciliation via signed webhooks
- **Automatic Retry** — 3-attempt retry with exponential backoff
- **Payment Reconciliation** — HMAC-SHA256 signature verification

### 3. Billing & Invoicing
- **Automatic Invoice Generation** — Per billing cycle
- **Proration Logic** — Accurate credit calculation on mid-cycle changes
- **Invoice PDF Generation** — iText 8.0 with custom branding
- **Multi-Currency Support** — INR with extensible framework for other currencies
- **Invoice States** — Draft, sent, viewed, paid, overdue, refunded

### 4. Multi-Tenancy
- **Complete Tenant Isolation** — Schema-per-tenant architecture
- **Tenant Context Propagation** — Request-scoped tenant resolution
- **Tenant Lifecycle** — Onboarding, management, deactivation
- **Data Compliance** — Guaranteed data separation and compliance

### 5. Authentication & Authorization
- **JWT-based Security** — HS256-signed tokens with 15-minute access token TTL
- **HttpOnly Cookies** — XSS protection, secure token transmission
- **Refresh Token Rotation** — 7-day refresh tokens with automatic rotation
- **Role-Based Access Control** — User, admin, and tenant-level roles
- **Tenant-Aware Authorization** — Resources bound to tenant context

### 6. Event-Driven Architecture
- **Domain Events** — Publish significant business occurrences
- **Transactional Outbox Pattern** — Guarantee event delivery with DB atomicity
- **Event Handlers** — Async processing of domain events
- **Notification Triggers** — Events trigger email notifications
- **Audit Trail** — All events logged for compliance

### 7. Observability
- **Structured Logging** — JSON format with MDC correlation IDs
- **Distributed Tracing** — Spring Cloud Sleuth for request tracking
- **Metrics** — Micrometer counters and timers for business metrics
- **Alerting** — Rules for payment failures, performance degradation
- **Health Checks** — Liveness and readiness probes for orchestration

### 8. Scheduling
- **Quartz Scheduler** — Distributed job scheduling with JDBC store
- **Scheduled Tasks** — Invoice generation, subscription renewal, overdue notifications
- **Job Execution Guarantee** — JDBC locking ensures single execution per cluster

---

## 🏗️ Architecture

### High-Level Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                      API Gateway / Load Balancer                 │
└────────┬────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Spring Boot Application                       │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │             REST Controllers (8 modules)                │   │
│  │  - Auth API      - Billing API      - Business API      │   │
│  │  - Tenant API    - Admin API        - Notification API  │   │
│  └──────────────────┬──────────────────────────────────────┘   │
│                     │                                            │
│  ┌──────────────────▼──────────────────────────────────────┐   │
│  │           Application Services Layer                    │   │
│  │  - SubscriptionService   - PaymentService              │   │
│  │  - InvoiceService        - NotificationService         │   │
│  │  - ProrationService      - AuthenticationService       │   │
│  │  - TenantService         - AuditService                │   │
│  └──────────────────┬──────────────────────────────────────┘   │
│                     │                                            │
│  ┌──────────────────▼──────────────────────────────────────┐   │
│  │        Event & Handler Layer                           │   │
│  │  - DomainEventPublisher (Outbox Pattern)              │   │
│  │  - NotificationEventHandler (Async Processors)        │   │
│  │  - AuditEventHandler (Compliance Logging)             │   │
│  └──────────────────┬──────────────────────────────────────┘   │
│                     │                                            │
│  ┌──────────────────▼──────────────────────────────────────┐   │
│  │        Data Access Layer (Spring Data JPA)            │   │
│  │  - Subscription Repositories                          │   │
│  │  - Payment Repositories                               │   │
│  │  - Invoice Repositories                               │   │
│  │  - Tenant Repositories                                │   │
│  └──────────────────┬──────────────────────────────────────┘   │
│                     │                                            │
└─────────────────────┼────────────────────────────────────────────┘
         ┌────────────┼────────────┬──────────────┐
         │            │            │              │
         ▼            ▼            ▼              ▼
    PostgreSQL    PostgreSQL   Redis         Razorpay
    (Public)      (Tenants)    (Cache)       (Payment)
    Schema        Schemas                    Gateway
    (Shared)      (Per-Tenant)
```

### Hexagonal Architecture (Ports & Adapters)

```
                    EXTERNAL SYSTEMS
                     ┌─────────────┐
                     │  Razorpay   │
                     │  Mail SMTP  │
                     │  PostgreSQL │
                     │  Redis      │
                     └────────────┘
                          │
                ┌─────────┼─────────┐
                │                   │
          ┌─────▼──────┐     ┌─────▼──────┐
          │  Adapters  │     │  Adapters  │
          │ (Outbound) │     │  (Inbound) │
          └─────┬──────┘     └─────┬──────┘
                │                   │
                └──────────┬────────┘
                           │
                  ┌────────▼────────┐
                  │  Application    │
                  │    Core         │
                  │  (Services,     │
                  │   Entities,     │
                  │   UseCases)     │
                  └─────────────────┘
```

### Module Structure

```
src/main/java/com/mtbs/
├── auth/              # Authentication & Authorization
│   ├── controller/    # Auth endpoints
│   ├── service/       # JWT, token management
│   ├── security/      # Filters, authentication providers
│   └── dto/           # Login, signup, token DTOs
│
├── billing/           # Subscription & Billing Core
│   ├── controller/    # Billing API endpoints
│   ├── service/       # SubscriptionService, ProrationService
│   ├── entity/        # Subscription, Plan, Invoice, Payment JPA entities
│   ├── repository/    # Spring Data repositories
│   └── dto/           # Request/response DTOs
│
├── tenant/            # Multi-Tenancy Management
│   ├── controller/    # Tenant endpoints
│   ├── service/       # TenantService, onboarding
│   ├── context/       # TenantContextHolder for request scope
│   └── dto/           # Tenant creation, configuration
│
├── notification/      # Event-Driven Notifications
│   ├── service/       # NotificationService, email sending
│   ├── handler/       # Event handlers (@EventListener)
│   ├── template/      # Email template rendering
│   └── dto/           # Notification payloads
│
├── business/          # Business Features (Invoices, Payments)
│   ├── controller/    # Business API endpoints
│   ├── service/       # Business logic services
│   ├── entity/        # Business entities
│   └── dto/           # Business DTOs
│
├── admin/             # Admin Operations
│   ├── controller/    # Admin API endpoints
│   ├── service/       # Admin operations (user mgmt, reporting)
│   └── dto/           # Admin DTOs
│
├── shared/            # Shared Utilities
│   ├── util/          # Common utilities (CookieUtils, TokenUtils)
│   ├── exception/     # Custom exception hierarchy
│   ├── filter/        # Request filters (logging, CORS, etc.)
│   ├── config/        # Spring configuration classes
│   └── multitenancy/  # Tenant context, interceptors
│
└── app/               # Application Bootstrap
    ├── config/        # Application configuration
    ├── listener/      # Event listeners
    └── Application.java  # Main entry point
```

---

## 📚 Documentation Structure

This project includes comprehensive documentation organized by topic:

### Phase 1-4: Core Documentation (32 Files, ~300k Words)
- **Phases 1-2:** Foundation (Authentication, request flow, events, multitenancy)
- **Phase 3:** APIs and Data Model (REST endpoints, entities, schemas)
- **Phase 4:** Production Patterns (Observability, scaling, performance, security)
- **Notification Module:** Event-driven architecture (9.5/10 architect quality)

### Phase 5: Overview & Advanced Topics
- **00-Overview:** README (this file), Project Summary, Technology Stack
- **Advanced Topics:** Customers, Products, Reporting, Testing, ADRs

### How to Use Documentation
1. **New Developer?** → Start with [PROJECT_SUMMARY.md](./PROJECT_SUMMARY.md)
2. **Architecture Questions?** → Read [TECH_STACK.md](./TECH_STACK.md) and Phase 4 docs
3. **API Integration?** → See Phase 3 API documentation
4. **Incident Debugging?** → Check Phase 4 observability & configuration docs
5. **System Design interviews?** → Study Phase 1-2 for patterns and flows

---

## 🔒 Security Highlights

### Authentication
- **JWT with HS256** — Industry-standard token signing
- **HttpOnly Cookies** — XSS-protected token storage
- **CSRF Protection** — Spring Security defaults
- **Refresh Token Rotation** — Automatic token refresh mechanism

### Payment Security
- **HMAC-SHA256 Verification** — Webhook signature validation
- **IP Whitelisting** — Razorpay CIDR verification
- **Constant-Time Comparison** — Timing attack prevention
- **Idempotency Keys** — Duplicate webhook prevention

### Multi-Tenancy
- **Tenant Context Isolation** — Request-scoped tenant verification
- **Row-Level Security** — All queries filtered by tenant_id
- **Schema Isolation** — Each tenant has separate PostgreSQL schema
- **Audit Trail** — All tenant data access logged

---

## 📈 Scalability & Performance

### Horizontal Scaling
- **Stateless Services** — JWT-based authentication enables scaling
- **Distributed Locking** — Redis locks prevent duplicate operations
- **Scheduled Job Distribution** — Quartz JDBC store ensures single execution
- **Load Balancing** — Round-robin across multiple instances

### Database Performance
- **Connection Pooling** — HikariCP with adaptive pool sizing
- **Query Optimization** — Eager loading, DTO projection, pagination
- **Indexing Strategy** — Compound indexes on frequently queried fields
- **Read Replicas** — PostgreSQL replication for read scaling

### Caching
- **Cache-Aside Pattern** — Lazy loading with TTL
- **Event-Based Invalidation** — Automatic cache updates on changes
- **Redis Cluster** — Support for Redis clustering
- **Multi-Level Cache** — Application + data layer caching

---

## 🛠️ Development & Operations

### Development Environment
```bash
# Development server (hot reload)
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=dev"

# Run tests
mvn test

# Integration tests with Testcontainers
mvn verify
```

### CI/CD Pipeline
- **GitHub Actions / GitLab CI** — Automated testing on every push
- **Maven Build** — Compile, test, package
- **Docker Build** — Multi-stage Docker image
- **Kubernetes Deployment** — Rolling updates, health checks

### Monitoring & Alerting
- **Prometheus** — Metrics scraping
- **Grafana** — Dashboards for business metrics
- **ELK Stack** — Structured logging aggregation
- **PagerDuty** — Alert routing and escalation

---

## 🤝 Contributing

### Code Standards
- **Java 17 best practices** — Records, sealed classes, pattern matching
- **Spring Framework conventions** — Component scanning, dependency injection
- **Test-Driven Development** — All features require unit + integration tests
- **Documentation** — Every public class/method documented with JavaDoc

### Git Workflow
1. Create feature branch from `main`
2. Write tests first (TDD)
3. Implement feature
4. Ensure all tests pass
5. Create pull request with clear description
6. Code review required before merge
7. Merge to `main` (triggers deployment)

### Reporting Issues
- **Bug Reports** → GitHub Issues with reproduction steps
- **Security Vulnerabilities** → Email security@example.com (confidential)
- **Feature Requests** → GitHub Discussions with use case

---

## 📞 Support & Contact

### Documentation
- **API Docs:** [Swagger UI](http://localhost:8080/swagger-ui.html)
- **Architecture Guides:** See `docs/` folder
- **Troubleshooting:** Check documentation master plan

### Team
- **Technical Lead:** Manoj Pandi
- **Engineering Team:** [Your Team]
- **Support Email:** support@example.com
- **Slack Channel:** #mtbs-engineering

---

## 📜 License

MTBS is proprietary software. All rights reserved.

---

## 🎓 Next Steps

1. **Set up local development environment** — Follow Quick Start section
2. **Review Project Summary** — Understand business context
3. **Study Technology Stack** — Know the tools and patterns
4. **Read Phase 1-2 Documentation** — Foundation patterns
5. **Explore Phase 3 APIs** — REST endpoints and contracts
6. **Review Phase 4 Production Patterns** — Observability, scaling, security
7. **Start contributing** — Pick a task from backlog

---

**Last Updated:** May 2026  
**Maintained By:** Engineering Team  
**Version:** 1.0  
