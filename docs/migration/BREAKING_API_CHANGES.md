# Breaking API changes (Laravel vs Java)

None for the Angular-used auth happy path if cookies and `/api/v1` are preserved.

Documented differences (Phase 2):

| Item | Java | Laravel now | Impact |
|---|---|---|---|
| 1-character email local-part slug | Possible `a` from `a@x.com`, but login DTO requires slug min length 2 | Signup pads slugs shorter than 2 (`a` → `ax`) | New shops only; migrated slugs unchanged |
| `POST /customers` | Full CRUD | Implemented (pending shops still 403 `TENANT_PENDING_APPROVAL`) | — |
| `POST /admin/auth/refresh` | Implemented | Implemented (cache-backed UUID) | — |
| PDF `/business-invoices/{id}/download` and `/preview` | iText PDF bytes | Raw `application/pdf` (same paths, copyType, attachment vs inline) | Layout is Helvetica cash-memo, not pixel-identical iText |
| `schemaName` on `/auth/me` | PostgreSQL schema | Legacy `schema_*` string on new shops | Angular ignores it |
| Test database | Postgres | SQLite in phpunit | Not an API break |
