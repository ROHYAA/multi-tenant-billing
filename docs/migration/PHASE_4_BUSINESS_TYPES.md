# PHASE 4 — Business type catalog

**Date:** 2026-10-14  
**Scope:** Configurable `business_type` on `shop_settings`. No extra databases, no industry modules.

## Codes (no remapping)

Existing Java enum values stay as stored codes:

| Code | Label | Notes |
|---|---|---|
| `RETAIL` | Retail shop | Existing |
| `WHOLESALE` | Wholesale | Existing |
| `SERVICE` | Service | Existing |
| `RESTAURANT` | Hotel / restaurant / café | Existing code; label covers hotel/café |
| `PHARMACY` | Pharmacy | Existing |
| `OTHER` | Other | Existing |
| `GROCERY` | Grocery shop | Added |
| `MOBILE_ELECTRONICS` | Mobile / electronics shop | Added |
| `SALON` | Salon / barber shop | Added |
| `REPAIR` | Repair / service business | Added |

`RETAIL` is **not** rewritten to `GROCERY`. `RESTAURANT` is **not** rewritten to a new hotel code.

## API

- `GET /api/v1/business-types` — public catalog (`code`, `label`, `legacy`)
- `POST /api/v1/auth/signup` accepts optional `businessType`
- `PUT /api/v1/shop-settings` rejects unknown codes (`RES_4004`)

Table: `business_types` (platform, not per shop).

## Angular

Settings and signup load the catalog. If the Java API returns 404, settings fall back to the original six codes and signup hides the extra field so Java cutover is not required yet.

## Future extensions (not built)

Do not treat these as done:

- Restaurant tables / KOT / kitchen display
- Salon appointments and staff commissions
- Repair job tracking / IMEI / serial inventory
- Grocery barcodes / weighing scale
- Hotel rooms / POS table service

Generic customers, products, bills, payments, and reports remain the shared foundation.

## Tests

Result: **26 passed** (143 assertions). SQLite `:memory:`, not MySQL.
