# Multi-Tenant Billing System (MTBS)

[![Version](https://img.shields.io/badge/Version-0.0.1--SNAPSHOT-blue?style=flat-square)]()
[![Status](https://img.shields.io/badge/Status-In%20Development-yellow?style=flat-square)]()
[![Java](https://img.shields.io/badge/Java-17%20LTS-orange?style=flat-square)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.3-green?style=flat-square)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-14+-336791?style=flat-square)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-6.0+-dc382d?style=flat-square)](https://redis.io/)
[![License](https://img.shields.io/badge/License-MIT-yellow?style=flat-square)](LICENSE)

**Enterprise-grade SaaS billing platform** — Production-oriented multi-tenant architecture with schema-per-tenant isolation, transactional event processing, complex billing workflows, and Razorpay payment integration.

---

## Engineering Highlights

- **Schema-Per-Tenant Multitenancy** — Hard database isolation via PostgreSQL schemas, not Row-Level Security
- **Transactional Outbox Pattern** — Designed to prevent event loss through atomic transaction handling
- **Event-Driven Architecture** — Async handlers for notifications, webhooks, and audit logging
- **JWT Authentication** — Stateless token-based auth with token versioning for instant invalidation
- **RBAC with Permission Scoping** — Fine-grained, tenant-aware access control
- **2-Step Payment Processing** — Razorpay integration with signature verification and idempotency
- **Subscription Billing Engine** — Full lifecycle management with mid-cycle proration
- **Async Notifications** — Event-triggered email with Thymeleaf templates
- **Redis Caching** — Distributed cache for tenant resolution and permissions
- **Flyway Migrations** — Version-controlled schema management (shared + per-tenant)

---

## Quick Overview

MTBS enables independent SaaS tenants to:
- **Manage subscriptions** with flexible plan changes and automatic billing cycles
- **Process payments** securely via Razorpay with webhook verification
- **Generate invoices** with automatic proration for mid-cycle changes
- **Receive notifications** via event-driven email triggers
- **Control access** through role-based permissions scoped to each tenant

Each tenant is completely isolated in a dedicated PostgreSQL schema — no shared tables, no cross-tenant query risk.

---

## Why MTBS?

Most billing tutorials focus on simple CRUD operations. MTBS explores challenges commonly found in enterprise SaaS platforms:

- **Multi-tenancy** — Secure isolation of independent customer data
- **Subscription billing** — Complex state machines, plan changes, mid-cycle proration
- **Event-driven workflows** — Reliable event processing without data loss
- **Payment orchestration** — 2-step payment model with Razorpay integration
- **Tenant isolation** — Database-enforced schema separation vs. Row-Level Security trade-offs
- **Role-based access control** — Fine-grained permission scoping per tenant

Built for developers and architects wanting to understand how production SaaS platforms handle these problems at scale.

---

## Project Metrics

- **8 Core Modules** — Modular, SOLID-compliant architecture
- **250+ Classes** — Comprehensive domain models and services
- **13 Documentation Folders** — 40+ technical guides covering architecture to deployment
- **20+ Tenant Migrations** — Flyway-managed per-tenant schema provisioning
- **8 Scheduled Jobs** — Billing cycles, subscription transitions, event processing
- **JWT + RBAC** — Stateless authentication with token versioning & fine-grained permissions
- **Schema-Per-Tenant** — Database-level isolation for true multi-tenancy
- **Transactional Outbox** — Event reliability patterns for distributed systems

```
┌─────────────────────────────────────────────────────────┐
│                   HTTP Requests                         │
├─────────────────────────────────────────────────────────┤
│  JwtAuthenticationFilter → TenantContext (ThreadLocal)  │
├─────────────────────────────────────────────────────────┤
│  Hibernate Multitenancy Resolver                        │
│  (Automatically routes to tenant schema via search_path)│
├─────────────────────────────────────────────────────────┤
│  8 Modules: auth, billing, tenant, business,            │
│            notification, admin, shared, app             │
├─────────────────────────────────────────────────────────┤
│  PostgreSQL (Public + Per-Tenant Schemas)               │
│  Redis Cache                                            │ 
│  Razorpay Payment Gateway                               │
└─────────────────────────────────────────────────────────┘
```

**See detailed architecture:** [System Architecture](docs/01-architecture/system-design.md) | [Request Flow](docs/01-architecture/request-flow.md) | [Event Flow](docs/08-events/event-flow.md)

---

## Key Features

### Multi-Tenancy
- ✅ **Schema-Per-Tenant Isolation** — Each tenant in dedicated `s_{tenantId}` schema
- ✅ **Automatic Schema Routing** — Hibernate multitenancy provider handles `SET search_path`
- ✅ **Flyway Migrations** — Public schema (shared) + per-tenant schemas (20+ migrations each)
- ✅ **Cross-Tenant Safety** — Database-level isolation prevents cross-tenant queries

**[Learn more →](docs/02-multi-tenancy/)**

### Security & Authentication
- ✅ **JWT (HS256)** — Stateless tokens with 15-min access + 7-day refresh TTL
- ✅ **HttpOnly Cookies** — Tokens stored securely with SameSite=Strict
- ✅ **Token Versioning** — Instant token invalidation on logout (Redis-backed, zero DB writes)
- ✅ **RBAC with Permissions** — Fine-grained controls (BILLING_MANAGE, USER_MANAGE, etc.)
- ✅ **Webhook Verification** — HMAC-SHA256 signature validation for Razorpay

**[Learn more →](docs/03-security/authentication.md) | [Authorization](docs/03-security/authorization-rbac.md)**

### Billing & Payments
- ✅ **Subscription Lifecycle** — TRIALING → ACTIVE → CANCELLED/EXPIRED state machine
- ✅ **2-Step Payment Model** — Order creation → Signature verification → Fund capture
- ✅ **Mid-Cycle Proration** — Daily rate calculations for plan changes
- ✅ **Invoice Management** — Auto-generation, payment tracking, refunds
- ✅ **Razorpay Integration** — Production-oriented with idempotency keys

**[Learn more →](docs/05-platform-billing/subscription-lifecycle.md) | [Payments](docs/05-platform-billing/payment-processing.md) | [Proration](docs/05-platform-billing/proration.md)**

### Event-Driven Architecture
- ✅ **Transactional Outbox** — Events & business data saved atomically (designed to minimize event loss)
- ✅ **Async Handlers** — @EventListener for email, webhooks, audit logging
- ✅ **At-Least-Once Delivery** — OutboxProcessor retries failed events
- ✅ **Idempotent Processing** — Safe to replay any event

**[Learn more →](docs/01-architecture/event-flow.md) | [Outbox Pattern](docs/01-architecture/outbox-pattern.md)**

### Infrastructure & Operations
- ✅ **Spring Boot 3.4.3** with Java 17 LTS
- ✅ **PostgreSQL 14+** with Flyway versioned migrations
- ✅ **Redis 6.0+** for caching, sessions, distributed locks
- ✅ **Docker Compose** for local development (PostgreSQL, Redis, Mailhog)
- ✅ **Spring Profiles** — dev (Flyway updates), prod (Flyway validates)

---

## Technology Stack

| Layer | Technology | Version | Purpose |
|-------|-----------|---------|---------|
| **Runtime** | Java | 17 LTS | Application language |
| **Framework** | Spring Boot | 3.4.3 | Web framework |
| **Database** | PostgreSQL | 14+ | Primary data store |
| **Migrations** | Flyway | 10.x | Version-controlled schema management |
| **ORM** | Hibernate | 6.x | Multi-tenancy provider (SCHEMA mode) |
| **Cache** | Redis | 6.0+ | Distributed cache + sessions |
| **Cache Client** | Lettuce | 6.x | Non-blocking Redis client |
| **Payment** | Razorpay SDK | 1.4.5 | Payment gateway integration |
| **Auth** | JJWT | 0.12.6 | JWT token creation/validation (HS256) |
| **Security** | Spring Security | 6.x | Authentication framework |
| **Testing** | JUnit 5 | 5.x | Unit/integration tests |
| **Build** | Maven | 3.8+ | Dependency management |
| **Containers** | Docker | 24+ | Containerization |

**[Full Tech Stack →](docs/00-overview/TECH_STACK.md)**

---

## Project Structure

```
src/main/java/com/mtbs/
├── app/              # Bootstrap, config, filters, global exception handling
├── auth/             # JWT, RBAC, login/signup, token management
├── tenant/           # Tenant onboarding, plan management, schema provisioning
├── billing/          # Platform billing: subscriptions, invoices, payments
├── business/         # B2B invoicing: customers, products, business invoices
├── notification/     # Async email delivery, event listeners, templates
├── admin/            # Cross-tenant operations, audit logs, metrics
└── shared/           # Common entities, domain events, utilities

src/main/resources/
├── application.yaml                   # Default config
├── application-{dev,prod}.yaml       # Environment-specific overrides
├── db/migration/
│   ├── public/                       # Shared schema migrations (8)
│   └── tenant/                       # Per-tenant schema migrations (20+)
└── templates/emails/                 # Thymeleaf email templates

docs/
├── architecture/                      # System design, request flows, decisions
├── security/                          # Authentication, authorization
├── billing/                           # Subscription, payment, proration details
└── decisions/                         # ADRs (Architectural Decision Records)
```

---

## Quick Start

### Prerequisites
- Java 17 LTS
- Maven 3.8+
- Docker & Docker Compose (recommended)

### Clone & Setup

```bash
# Clone repository
git clone <repository-url>
cd MultiTenantBillingSystem

# Copy environment config
cp .env.example .env.dev
# Edit .env.dev with Razorpay test credentials

# Start PostgreSQL, Redis, Mailhog
docker-compose up -d

# Build and run
mvn clean install
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=dev"
```

Application runs on `http://localhost:8080`

**[Full Setup Guide →](docs/00-overview/README.md) | [Project Summary](docs/00-overview/PROJECT_SUMMARY.md)**

### First API Call: Signup

```bash
curl -X POST http://localhost:8080/api/auth/signup \
  -H "Content-Type: application/json" \
  -d '{
    "email": "admin@acme.com",
    "password": "SecurePassword123!",
    "businessName": "Acme Corporation",
    "schemaName": "acme-corp"
  }'

# Response: { "success": true, "tenantId": 1 }
```

---

## API Overview

### Core Endpoint Groups

| Endpoint | Purpose |
|----------|---------|
| `/api/auth/**` | Login, signup, token refresh, password reset |
| `/api/subscriptions/**` | Subscription CRUD, upgrades, downgrades |
| `/api/invoices/**` | Invoice generation, payment, retrieval |
| `/api/payments/**` | Payment processing, verification, refunds |
| `/api/admin/**` | Platform admin: tenants, users, metrics, audit logs |
| `/api/webhooks/razorpay/**` | Razorpay payment notifications |

**[Full API Reference →](docs/06-api/)**

---

## Project Status

### Completed ✅
- [x] Multi-tenant schema-per-tenant architecture
- [x] JWT authentication with token versioning
- [x] RBAC with tenant-scoped permissions
- [x] Subscription lifecycle management
- [x] Razorpay 2-step payment integration
- [x] Proration algorithm
- [x] Transactional outbox pattern
- [x] Event-driven notifications
- [x] Redis caching & session management
- [x] Comprehensive documentation

### In Progress 🔄
- [ ] Enhanced observability (Prometheus metrics, Grafana dashboards)
- [ ] Advanced billing features (usage-based, discounts, taxes)

### Planned 📋
- [ ] Frontend dashboard (React/Vue)
- [ ] Kubernetes deployment manifests
- [ ] Distributed tracing (Jaeger)
- [ ] Advanced reporting and analytics
- [ ] Dunning management (automated payment recovery)
- [ ] Multi-currency support (beyond INR)

---

## Documentation

Deep technical documentation for each component:

### 📚 Documentation Structure

All documentation organized under `docs/` with 13 focused sections:

- **[00-overview](docs/00-overview/)** — Project summary, tech stack, index
- **[01-architecture](docs/01-architecture/)** — System design, request flow, architecture
- **[02-multi-tenancy](docs/02-multi-tenancy/)** — Schema isolation, tenant context, multi-tenant patterns
- **[03-security](docs/03-security/)** — Authentication, JWT, authorization, RBAC, webhook security
- **[04-business-modules](docs/04-business-modules/)** — B2B invoicing, customers, products
- **[05-platform-billing](docs/05-platform-billing/)** — Subscriptions, payments, proration, invoices
- **[06-api](docs/06-api/)** — REST endpoints, request/response specs
- **[07-data-model](docs/07-data-model/)** — Entity relationships, database schema
- **[08-events](docs/08-events/)** — Event-driven patterns, outbox pattern, event handlers
- **[09-jobs](docs/09-jobs/)** — Scheduled tasks, background jobs
- **[10-non-functional](docs/10-non-functional/)** — Performance, logging, monitoring
- **[11-testing](docs/11-testing/)** — Unit tests, integration tests, test strategies
- **[12-decisions](docs/12-decisions/)** — Architectural Decision Records (ADRs)

---

## Testing

```bash
# Run all tests
mvn test

# Run specific test
mvn test -Dtest=SubscriptionServiceTest

# With coverage
mvn clean test jacoco:report
open target/site/jacoco/index.html
```

Key test scenarios:
- Schema isolation (no cross-tenant leakage)
- Subscription state machine transitions
- Proration calculations
- Payment webhook verification
- Token invalidation on logout
- Billing cycle automation

---

## Contributing

Contributions welcome! Please:
1. Fork repository
2. Create feature branch (`git checkout -b feature/amazing-feature`)
3. Follow code style (existing patterns)
4. Add tests for new functionality
5. Submit pull request with clear description

---

## License

MIT License — See [LICENSE](LICENSE) for details.

---

## Support & Questions

For technical questions or issues:
- Check [documentation](docs/) for architectural details and guides
- Review [API reference](docs/06-api/) for endpoint specifics
- Open an issue for bugs or feature requests
- Check existing issues before creating new ones

---
