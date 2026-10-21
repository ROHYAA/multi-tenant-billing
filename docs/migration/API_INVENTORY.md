# API Inventory — ShopLedger (`/api/v1`)

All controllers use `api.version=v1`. JSON success envelope:

```json
{ "success": true, "message": "...", "data": {}, "timestamp": "..." }
```

Error envelope:

```json
{ "success": false, "message": "...", "errorCode": "AUTH_1xxx", "fieldErrors": {}, "timestamp": "..." }
```

Pagination (`PageResponse`): `content`, `page`, `size`, `totalElements`, `totalPages`, `first`, `last`, `empty`, `hasNext`, `hasPrevious`.

Spring pageable query params: `page`, `size`, `sort` (e.g. `createdAt,desc`).

PDF endpoints return raw bytes, **not** the envelope.

Tenant is **never** taken from the request body for data access. Login requires `tenantSlug` only to choose which shop schema to authenticate against.

Authority strings: tenant permissions are `PERMISSION_{NAME}`; platform admin is `SUPER_ADMIN`.

---

## A. Used by the Angular 20 app (must stay compatible)

### Auth — `AuthController` `/auth`

| Method | Path | Auth | Angular caller | Notes |
|---|---|---|---|---|
| POST | `/auth/signup` | Public | `AuthService.signup` | Creates shop `PENDING_APPROVAL`, schema, OWNER user, sets cookies. Body: name, email, password (see `SignupRequest`). |
| POST | `/auth/login` | Public | `AuthService.login` | Body: `{ email, password, tenantSlug }`. Cookies only; no tokens in `data`. |
| POST | `/auth/refresh` | Public (cookie) | `AuthService.refreshSession` | Refresh cookie path-scoped. Body may include `tenantSlug`. |
| POST | `/auth/logout` | Authenticated (or cookie clear) | `AuthService.logout` | Revokes refresh; clears cookies. |
| GET | `/auth/me` | Tenant JWT | `AuthService.bootstrapSession` | Flatter `UserProfileResponse` (not login shape). |

Cookie names: `access_token`, `refresh_token`. CORS credentials required for browser.

### Admin auth — `AdminAuthController` `/admin/auth`

| Method | Path | Auth | Angular |
|---|---|---|---|
| POST | `/admin/auth/login` | Public | `AdminAuthService.login` |
| POST | `/admin/auth/refresh` | Public (admin refresh cookie) | (interceptor excluded) |
| POST | `/admin/auth/logout` | `SUPER_ADMIN` | `AdminAuthService.logout` |
| GET | `/admin/auth/me` | `SUPER_ADMIN` | `AdminAuthService.checkSession` |

Separate cookies/paths from tenant auth (`CookieConstants.ADMIN_REFRESH_PATH`).

### Admin tenants — `AdminTenantController` `/admin/tenants`

Class-level `SUPER_ADMIN`.

| Method | Path | Angular |
|---|---|---|
| GET | `/admin/tenants` | list (`status` optional) |
| POST | `/admin/tenants/{id}/approve` | `{ planName, subscriptionExpiresAt }` |
| POST | `/admin/tenants/{id}/reactivate` | same body as approve |
| PUT | `/admin/tenants/{id}/status` | `{ status, reason }` |

Present in API, **not** called by current Angular admin page: GET `/{id}`, GET `/{id}/users`, DELETE `/{id}`.

### Customers — `CustomerController` `/customers`

| Method | Path | Permission | Angular |
|---|---|---|---|
| POST | `/customers` | CUSTOMER_MANAGE | create |
| GET | `/customers` | CUSTOMER_MANAGE | list/search pageable |
| GET | `/customers/walkin` | **BILLING_MANAGE** | default POS customer |
| GET | `/customers/{id}` | CUSTOMER_MANAGE | detail |
| PUT | `/customers/{id}` | CUSTOMER_MANAGE | update |
| DELETE | `/customers/{id}` | CUSTOMER_MANAGE | soft delete (walk-in protected) |

### Products — `ProductController` `/products`

| Method | Path | Permission | Angular |
|---|---|---|---|
| POST | `/products` | PRODUCT_MANAGE | create |
| GET | `/products` | PRODUCT_MANAGE | list |
| GET | `/products/active` | **BILLING_MANAGE** | POS catalog |
| GET | `/products/{id}` | PRODUCT_MANAGE | (service has it; list UI may not) |
| PUT | `/products/{id}` | PRODUCT_MANAGE | update |
| DELETE | `/products/{id}` | PRODUCT_MANAGE | deactivate |
| POST | `/products/{id}/reactivate` | PRODUCT_MANAGE | reactivate |

### Bills — `BillController` `/business-invoices`

Class-level `BILLING_MANAGE`. Path kept as `business-invoices` after table rename to `bills`.

| Method | Path | Angular | Behavior |
|---|---|---|---|
| POST | `/business-invoices` | create | DRAFT; server totals; invoice number from `number_series` |
| GET | `/business-invoices` | list, dashboard, customer summary | filters: `customerId`, `status` |
| POST | `/{id}/finalize` | create-bill chain | DRAFT→OPEN; due date +30 days |
| POST | `/business-payments/{id}` | create-bill chain | see payments |
| GET | `/{id}/download` | print | PDF blob; query `copyType` ORIGINAL\|DUPLICATE\|TRIPLICATE |

Not used by Angular today: GET `/{id}`, POST `/{id}/items`, DELETE `/{id}/items/{itemId}`, POST `/{id}/send`, POST `/{id}/void`, GET `/{id}/preview`.

Create body (`CreateBillRequest`):

```json
{
  "customerId": 1,
  "currency": "INR",
  "notes": null,
  "discountAmount": 0,
  "items": [
    { "productId": 10, "quantity": 2 },
    { "productId": null, "description": "Service", "quantity": 1, "unitPrice": 100, "taxPercentage": 18 }
  ]
}
```

If `productId` is set, server overwrites description/price/tax from the catalog.

### Payments — `PaymentController` `/business-payments`

Class-level `BILLING_MANAGE`.

| Method | Path | Angular |
|---|---|---|
| POST | `/{invoiceId}` | record against one bill |
| GET | `/invoice/{invoiceId}` | payment list |
| GET | `/invoice/{invoiceId}/outstanding` | outstanding number |
| POST | `/customer/{customerId}` | FIFO allocation |
| GET | `/customer/{customerId}/outstanding` | FIFO preview + customer detail |
| PATCH | `/{paymentId}/confirm` | confirm CREDIT PENDING |

Record body: `{ amount, method, notes?, paidAt? }`.  
Methods: `CASH|CARD|UPI|NETBANKING|BANK_TRANSFER|CREDIT`.

### Reports — `ReportController` `/reports`

Class-level `BILLING_MANAGE`.

| Method | Path | Angular |
|---|---|---|
| GET | `/reports/revenue?from&to` | ISO instants |
| GET | `/reports/outstanding` | OPEN bills |
| GET | `/reports/monthly?year` | 12 rows |

### Shop settings — `ShopSettingsController` `/shop-settings`

| Method | Path | Authz | Angular |
|---|---|---|---|
| GET | `/shop-settings` | any authenticated tenant user | get |
| PUT | `/shop-settings` | TENANT_MANAGE | partial update |

Fields: see `shop-settings.model.ts` / `DATABASE_MAPPING.md`. `businessType` current enum: `RETAIL|WHOLESALE|SERVICE|RESTAURANT|PHARMACY|OTHER`.

### Bill templates — `BillTemplateController` `/bill-templates`

| Method | Path | Angular |
|---|---|---|
| GET | `/bill-templates` | settings page |

Public catalog; no write API.

### Attachments — `AttachmentController` `/attachments`

| Method | Path | Authz | Angular |
|---|---|---|---|
| POST | `/attachments?purpose=` | TENANT_MANAGE, multipart `file` | logo/signature upload |
| GET | `/{id}/file` | authenticated tenant (no TENANT_MANAGE) | `<img src>` |

Purpose: `LOGO|SIGNATURE|STAMP|QR_CODE|OTHER`. GET metadata and DELETE exist; settings UI uses upload + file URL.

---

## B. Backend exists; Angular does not call (preserve or document as unused)

### Auth extras

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/auth/tenants` | **Not public** in `SecurityConfig` | Email → tenant list; anti-enumeration 200. Unused. |
| POST | `/auth/forgot-password` | Public | `{ tenantSlug, email }` always 200 |
| POST | `/auth/reset-password` | Public | token + new password |

### Tenant self-service — `ShopController` `/tenant`

| Method | Path | Authz |
|---|---|---|
| GET | `/tenant` | authenticated |
| PUT | `/tenant` | TENANT_MANAGE |
| GET | `/tenant/schema` | TENANT_MANAGE |
| GET | `/tenant/status` | authenticated |
| POST | `/tenant/deactivate` | TENANT_MANAGE + OWNER role check in service |
| POST | `/tenant/reactivate` | TENANT_MANAGE |

### Users / roles / permissions

`UserController` `/users`, `RoleController` `/roles`, `PermissionController` `/permissions` — full CRUD + role assignment. Angular has no screens. Still required if OWNER/ADMIN/EMPLOYEE RBAC stays server-side (signup seeds roles).

### Bills unused verbs

Add/remove line items on DRAFT, send email+PDF, void, preview PDF, get-by-id. POS UI skips item-edit APIs because it creates a complete bill in one POST.

### Admin unused

GET tenant detail, list tenant users, DELETE tenant (soft, schema **not** dropped).  
`AuditLogController` `/admin/audit-logs` (filters, statistics).  
`AdminUserController` `DELETE /admin/users/tenant/{tenantId}/user/{userId}`.

### Health

`GET /health` public.

---

## C. Legacy SaaS billing (compiled; tables dropped; Angular unused)

Do **not** rebuild these for ShopLedger unless product requires SaaS subscriptions again.

| Controller | Base path |
|---|---|
| `legacy...PlanController` | `/plans` |
| `legacy...AdminMetricsController` | `/admin/metrics` |
| `legacy...DashboardController` | `/dashboard` |
| `legacy...InvoiceController` | `/invoices` (platform) |
| `legacy...PaymentController` | `/payments` (platform) |
| `legacy...SubscriptionController` | `/subscriptions` |
| `legacy...UsageController` | `/usage` |
| `legacy...RazorpayWebhookController` | `/webhooks/razorpay` |

Collision risk: `/dashboard` vs Angular dashboard (Angular uses reports + bills, not this controller). `/payments` vs `/business-payments`.

---

## D. Error codes (sample — `ErrorCode` enum)

Families: `AUTH_1xxx`, `TNT_2xxx`, `TKN_3xxx`, `RES_4xxx`, `PAY_5xxx`, `VAL_6xxx`, `SUB_7xxx`, `GEN_9001`.

Filter-generated codes (not always the enum): `TENANT_PENDING_APPROVAL`, `TOKEN_REVOKED`, `UNAUTHORIZED`.

Laravel should keep `errorCode` strings the Angular snackbars / `ApiError` already surface.

---

## E. Compatibility contract for Laravel (Phase 2+)

Must keep unless a breaking-change log is approved:

1. Prefix `/api/v1`
2. Envelope + `PageResponse` field names (camelCase JSON)
3. Cookie names and refresh path
4. Path names `/business-invoices` and `/business-payments` (not `/bills`)
5. Permission names and `PERMISSION_` prefix in Spring Security (Laravel gates can map internally but JWT/session authorities Angular stores are **unprefixed** names like `BILLING_MANAGE` from `/auth/me`)
6. Confirm Angular `current-user.model.ts` permission strings vs JWT — UI checks `permissions.includes('BILLING_MANAGE')` **without** `PERMISSION_`
7. Instant timestamps as ISO-8601
8. Enum strings uppercase as today
9. Blob PDF `Content-Disposition` attachment vs inline for download/preview
10. Multipart attachment `purpose` query param

Document every deviation in a Phase 2 `BREAKING_API_CHANGES.md` (not created in Phase 0).
