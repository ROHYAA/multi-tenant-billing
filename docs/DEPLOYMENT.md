# Free Testing Deployment (Render)

Deploys the whole app — backend API + the Angular frontend bundled into the
same image (see `Dockerfile`) — as a single free Render web service, with a
free Postgres and a free Redis-compatible Key Value instance, all under one
Render account. No other signups needed.

**This is a testing deployment, not production-grade.** See "Free-tier
caveats" at the bottom before relying on it for anything real.

## What changed to make this possible

- `Dockerfile` now builds the Angular frontend first and copies its output
  into the backend's `src/main/resources/static` before packaging, so one
  Docker image serves both. This avoids cross-origin cookie/CORS complexity
  for the JWT cookie auth flow — the frontend already assumed same-origin in
  production (`frontend/src/environments/environment.prod.ts`).
- `SecurityConfig` now permits the bundled SPA's own static files/routes to
  anonymous visitors (previously `anyRequest().authenticated()` would have
  401'd the login page itself). All `/api/**` authorization is unchanged.
- `GlobalExceptionHandler` forwards unmatched non-API paths (e.g. a hard
  refresh on `/dashboard`) to `index.html` so Angular's client-side router
  still works; an unmatched `/api/**` path stays a plain 404 as before.
- `application.yaml`: `server.port` now reads `${PORT:8080}` (Render sets
  `PORT`). Redis's URL isn't wired via an explicit YAML placeholder — that
  was tried and reverted (an unset value bound to `""` rather than truly
  absent, which broke Spring's Redis autoconfiguration locally). Instead,
  `render.yaml` sets `SPRING_DATA_REDIS_URL` directly, which Spring Boot
  binds to `spring.data.redis.url` on its own via standard relaxed env-var
  binding, with no YAML changes needed and no effect on local/dev/test.

All of this was verified locally end-to-end (full `mvn test` suite passing,
plus a manual smoke test: signup → cookie set → authenticated API call →
SPA deep link → static asset → unmapped-API 404, all correct) before writing
this guide.

## One-time setup

### 1. Push this repo to GitHub

```
git remote add origin https://github.com/<your-username>/<repo-name>.git
git push -u origin master
```

(Create the empty repo on GitHub first if you haven't.)

### 2. Create a Render account and deploy the Blueprint

1. Sign up at [render.com](https://render.com) (free) and connect your
   GitHub account.
2. Dashboard → **New** → **Blueprint** → select this repo. Render reads
   `render.yaml` and provisions three things:
   - `mtbs-app` — the web service (Docker build of backend + bundled frontend)
   - `mtbs-db` — a free Postgres instance
   - `mtbs-redis` — a free Key Value (Redis-compatible) instance

   `mtbs-redis`'s connection string and `mtbs-db`'s username/password are
   wired automatically. A few values are marked `sync: false` in
   `render.yaml` because Render can't know them in advance — you'll fill
   these in after creation (Dashboard → `mtbs-app` → Environment):

   | Env var | Where to get it |
   |---|---|
   | `DATABASE_URL` | `mtbs-db` → Info page → "External Database URL", **prefix it with `jdbc:`** (Spring needs a JDBC URL, not a plain Postgres URI) |
   | `FRONTEND_URL` | `mtbs-app`'s own public URL, shown after the first deploy (e.g. `https://mtbs-app.onrender.com`) |
   | `CORS_ALLOWED_ORIGINS` | same value as `FRONTEND_URL` |

3. The first deploy will likely fail its health check until `DATABASE_URL`
   is set (chicken-and-egg: the app needs the DB URL, but the DB only
   exists after the blueprint runs once). That's expected — set the three
   values above, then trigger **Manual Deploy** → **Deploy latest commit**.

### 3. Verify

Visit the `mtbs-app` URL. Sign up a shop, log in, create a customer/bill,
generate a PDF. If the first request after a while feels slow, that's the
free tier's cold start (see below) — not a bug.

## Free-tier caveats (why this is testing-only)

- **Backend cold starts.** The free web service spins down after 15 minutes
  of inactivity; the next request triggers a ~30-60s cold start.
- **Postgres expires.** Render's free Postgres is deleted 30 days after
  creation (14-day grace period to upgrade first). For a longer-lived test
  instance, recreate the database or upgrade the plan before it expires.
  Render emails you ahead of both deadlines.
- **Redis is not persistent.** Render's free Key Value instance is
  memory-only — data is lost on restart/maintenance. This only matters here
  because it's used purely as a cache (tenant schema-name resolution, plan/
  usage/dashboard caches) — losing it just means a cache miss and a normal
  DB re-query, not data loss. Don't repurpose it for anything that needs to
  survive a restart.
- **512MB RAM / 0.1 CPU** on the web service — fine for solo testing, not
  for load testing or multiple concurrent real users.
- **Fake Razorpay/mail config.** `render.yaml` ships placeholder Razorpay
  keys and an unreachable mail host — payments and outgoing email are not
  functional on this deployment. Signup/login/billing all work regardless
  (`NotificationService` is `@Async` and swallows send failures).

## Rolling back to local-only

Nothing about local `mvn spring-boot:run` / `docker-compose up` changed —
`PORT` is optional and defaults to prior behavior when
unset.
