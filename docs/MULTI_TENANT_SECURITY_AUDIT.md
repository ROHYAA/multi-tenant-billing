# Multi-Tenant Security Audit — Cross-Tenant Data Isolation

**Date:** 2026-08-19
**Scope:** Pre-deployment audit of cross-tenant data isolation across every resource type and access pattern exposed by the public REST API.
**Verdict: PASS**
**P0 issues found: 0. P1 issues found: 0.**

No architectural changes were made. One pre-existing, unrelated defect (unrenderable attachment
formats silently accepted) was found and fixed during adjacent work in this session; it is not a
cross-tenant isolation issue and is documented separately below for completeness.

---

## 1. Architecture under test

MTBS uses **schema-per-tenant** multi-tenancy: every shop gets its own dedicated PostgreSQL schema,
not a shared-table-plus-`tenant_id`-column model. Isolation is enforced at the connection level, not
in query predicates:

- `JwtAuthenticationFilter` is the **only** place `TenantContext` is populated, and it is populated
  exclusively from validated JWT claims — never from a path variable, query parameter, or request
  body field.
- `CurrentTenantIdentifierResolverImpl` (Hibernate `CurrentTenantIdentifierResolver`) reads the
  active tenant from `TenantContext` for every session.
- `SchemaBasedMultiTenantConnectionProvider` issues `SET search_path TO "<schema>", public` on every
  connection checkout, and resets to `public` on release.
- `SchemaCacheService` resolves `tenantId → schemaName` via a Redis-cached lookup (`"schema:" +
  tenantId`), with no key-collision risk.
- `TenantContext` itself is `ThreadLocal`-based, not a shared mutable static.

Because routing happens once, at the connection level, before any repository query runs, an
application-layer bug in one controller cannot leak data the way it could in a shared-table design —
a controller that forgot a `WHERE tenant_id = ?` clause would simply run its query against the
correct tenant's schema regardless, not against every tenant's rows. The main residual risk class is
therefore **not** "did every query filter by tenant" but "can `TenantContext` ever be set from
anything other than a validated JWT" — confirmed no (see above) — and "are admin-only endpoints
correctly gated so a regular tenant can't reach cross-tenant admin operations."

`AdminUserController`, `AuditLogController`, and `AdminTenantController` are all class-level
`@PreAuthorize("hasAuthority('SUPER_ADMIN')")`-gated, and a regular tenant JWT does not carry that
authority. Confirmed empirically (see §4, bonus check).

## 2. Methodology

### 2.1 Why raw status-code assertions are invalid here

Fresh tenant schemas have **colliding auto-increment primary keys** — tenant A's first customer and
tenant B's first customer are both `id=1` in their own schemas. A naive test of the form "attacker
requests victim's resource by ID, expect 403/404" is invalid: because routing is schema-based, that
request resolves to the *attacker's own* same-numbered local record, not the victim's. A `200 OK` in
that case is the **correct, expected** result, not a leak. An early draft of this audit used
status-code assertions and initially reported near-total failure; that was a test-methodology defect,
not an application defect, and was discarded in favor of the approach below.

### 2.2 Two properties actually verified

1. **Content-based leak detection.** Every victim record is created with fields containing a unique,
   unguessable marker string (`SECRET-<label>-<uuid>`). Every attacker-side response — regardless of
   HTTP status — is checked to confirm the victim's marker never appears in it. This is the correct
   test for "did any of the victim's actual data leak," independent of status codes and ID collision.
2. **Integrity-based mutation detection.** For every attacker PUT/DELETE/POST attempt against a
   victim's resource ID, the victim's own session re-fetches that resource **immediately before and
   immediately after** the attacker's request and asserts it is unchanged. This is correct even when
   the attacker's request coincidentally succeeds — because schema routing means it succeeded against
   the attacker's own same-numbered record, not the victim's.

   The "immediately before/after" framing matters: an early version of this check instead compared
   against a baseline captured once at setup time. That produced two apparent failures (product
   deactivation, attachment deletion) that were entirely explained by the **victim's own prior
   self-inflicted side effect**: in the first attack direction, tenant A (as attacker) deleting "B's"
   product ID actually deactivates *A's own* same-numbered product, as a side effect of A's own
   request being routed to A's schema. That's expected and harmless — but it meant that by the time
   the reverse direction ran and checked A's baseline, A's own product had already changed for reasons
   with nothing to do with B's attack. Switching to an immediate before/after around each specific
   attack call eliminates this class of false positive; both checks now correctly show
   `before == after` in both directions. See the regression test's inline comments for the same
   reasoning encoded directly next to the assertions.

### 2.3 Test instruments

Two independent instruments were used, deliberately overlapping:

- **Exploratory Python script** (ad hoc, not part of the repo) — broad coverage across every resource
  type and access pattern requested, run interactively against the live dev backend, used to develop
  and validate the methodology in §2.2 before committing anything to the codebase.
- **`CrossTenantSecurityIntegrationTest`** (`src/test/java/com/mtbs/integration/`) — the permanent
  regression test, added to the actual test suite. It drives two tenants entirely through real HTTP
  requests with real cookie-based JWT sessions (`TestRestTemplate` against a `RANDOM_PORT` embedded
  server), exercising the same code path a real attacker would use — unlike the pre-existing
  `MultiTenancyIntegrationTest`, which manipulates `TenantContext` directly and bypasses HTTP/JWT
  entirely (its core assertion is `assertTrue(true)` and does not meaningfully test isolation). The
  new test is intentionally narrower in resource coverage than the exploratory script (it covers the
  representative, highest-value cases per resource type) but is the one that runs on every future
  build.

## 3. Coverage

Both instruments tested every resource type and access pattern in scope:

| Resource | GET by ID | PUT by ID | DELETE by ID | Search | List/Pagination | Body-ID manipulation |
|---|---|---|---|---|---|---|
| Customers | ✅ | ✅ | ✅ | ✅ | — | — |
| Products | ✅ | ✅ | ✅ (deactivate) | ✅ | — | — |
| Bills | ✅ | — | — | — | ✅ (list + `customerId` filter) | ✅ (create bill claiming victim's `customerId`) |
| Bill PDFs | ✅ (preview + download, content-scanned) | — | — | — | — | — |
| Payments | ✅ (list by invoice) | — | — | — | — | ✅ (fraudulent payment against victim's bill, outstanding-balance integrity checked) |
| Attachments | ✅ (metadata + file bytes) | — | ✅ | — | — | — |
| Shop Settings | ✅ (no ID param — verified always caller's own) | — | — | — | — | — |
| Reports (revenue, outstanding) | ✅ (content-scanned) | — | — | — | — | — |
| Admin endpoints | — | — | — | — | — | ✅ (privilege-tier boundary: regular JWT rejected) |

Access patterns explicitly tested per the request: direct API requests, changing path IDs, changing
query parameters, changing request-body IDs, using a valid Shop A session against Shop B resources
(and vice versa), and attempting Shop B attachment access from Shop A.

## 4. Results

**Exploratory script, final run: 45 / 45 checks passed.** (Two earlier apparent failures were the
setup-time-baseline test artifact described in §2.2 — reproduced, root-caused, and fixed in the test
script; the corrected methodology showed the same two checks passing on rerun with `before == after`
in both directions, confirming no cross-tenant effect ever occurred.)

**Regression test, `CrossTenantSecurityIntegrationTest`: 2 / 2 test methods passed** (one per attack
direction, each asserting the customer/product/bill/payment/attachment/shop-settings properties in
§3). Verified via `mvn test -Dtest=CrossTenantSecurityIntegrationTest`.

Both same-numbered-ID checks confirmed each tenant's own session resolves only its own data at a
colliding ID, never the other tenant's — direct evidence that schema routing, not query-level
filtering, is what's providing isolation.

The admin-endpoint bonus check confirmed a regular tenant session gets `401` against
`/admin/tenants`.

## 5. Findings

**No cross-tenant data isolation vulnerability was found.** Zero P0 (data leak/mutation/deletion
across tenants) and zero P1 issues.

Two false positives were investigated during this audit and both were determined to be test-script
artifacts, not application defects — see §2.2 for the detailed root cause of each. No application
code was changed as a result, per the "no architectural changes unless a genuine vulnerability is
found" instruction governing this audit.

One unrelated, pre-existing defect was found and fixed during adjacent session work (not part of this
audit's cross-tenant scope, noted here for completeness): `AttachmentService` previously accepted
`image/webp` uploads that iText's PDF renderer cannot decode, so a shop's uploaded logo/signature
would silently fail to render on bills with no error surfaced anywhere. Fixed by removing `webp` from
`ALLOWED_CONTENT_TYPES` (now `png`/`jpeg` only) and the corresponding frontend file-input `accept`
attribute. This is a rendering-correctness bug, not a security or isolation issue, and does not affect
this audit's verdict.

## 6. Regression coverage going forward

`src/test/java/com/mtbs/integration/CrossTenantSecurityIntegrationTest.java` now runs as part of the
normal `mvn test` suite and will fail the build if cross-tenant isolation regresses for any of the
resource types it covers. It is intentionally written against real HTTP + JWT, not against
`TenantContext` directly, so it exercises the same trust boundary a real attacker would.

## 7. Verdict

**PASS.** No genuine cross-tenant vulnerability was found across any tested resource type or access
pattern. Architecture (JWT-only `TenantContext` derivation, per-checkout schema switching, Redis
cache keying, admin-endpoint privilege gating) held up under empirical attack in both directions.

**P0 issues: none. P1 issues: none.**

Per explicit instruction, **deployment has not been started** as part of this task.
