# Nnomm flash-deal claim service

Kotlin (Ktor 3, JVM 17+) and PostgreSQL 16. Restaurants publish limited-stock
flash deals. Customers claim 1–3 units, and a signed payment webhook confirms or
refunds each claim. It stays correct under concurrent claims, client retries,
duplicate or out-of-order webhooks, and payment/expiry races, with two instances
sharing one database. See [DESIGN.md](DESIGN.md) for how and why.

## Run it

```bash
cp .env.example .env        # then put real random values in JWT_SECRET and WEBHOOK_SECRET
docker compose up --build
```

This starts PostgreSQL and two instances: **app1 on :8080** and **app2 on :8081**.
Both run the Flyway migrations at startup (Flyway takes a Postgres advisory lock,
so they apply them only once).

| Variable | Required | Default |
|---|---|---|
| `DATABASE_URL` | yes | (`jdbc:postgresql://…` or `postgres://…`) |
| `DATABASE_USER`, `DATABASE_PASSWORD` | yes | compose: `flashdeals` |
| `JWT_SECRET`, `WEBHOOK_SECRET` | yes | none; never committed |
| `CLAIM_TTL_SECONDS` | no | `600` |
| `PORT` | no | `8080` |
| `EXPIRY_INTERVAL_MILLIS`, `DB_POOL_SIZE` | no | `1000`, `20` |

Extra endpoints: `GET /health`, and `GET /metrics` (Prometheus format). Logs are
JSON lines carrying a `request_id` (taken from `X-Request-Id` or generated, and
echoed back in the response).

## Test it

```bash
./gradlew test
```

All 45 tests run against **real PostgreSQL**:
- Testcontainers (`postgres:16-alpine`) when Docker is available.
- Otherwise an embedded PostgreSQL 16 binary (`io.zonky`).
- Or your own database: set `TEST_DATABASE_URL`, `TEST_DATABASE_USER` and `TEST_DATABASE_PASSWORD`.

The integration tests start **two real Netty servers with separate connection
pools** on one database, and alternate requests between them.

| Required scenario | Test |
|---|---|
| 200+ concurrent claims never oversell a 50-unit deal | `ClaimConcurrencyTest` (250 requests; also a mixed-quantity variant) |
| 20+ concurrent requests, one key → one claim | `ClaimConcurrencyTest` (25 requests, all get the same body, 24 replayed) |
| One customer, different keys → one claim | `ClaimConcurrencyTest` (30 requests) |
| Bad signatures, stale timestamps, duplicates, out-of-order | `WebhookTest`, `WebhookSignatureTest` |
| Payment racing expiry keeps the stock invariant | `PaymentExpiryRaceTest` (150 payments within ±300 ms of expiry, both instances sweeping every 20 ms) |
| Claim visibility for other customers and admins | `AuthAndVisibilityTest` |

Also covered: replay across instances, 422 on key reuse, a stored `SOLD_OUT`
staying `SOLD_OUT`, 400/404 responses not being stored, JWT attacks (`alg:none`,
HS512, tampered payload, expired, missing `exp`), validation, 413, and
`DEAL_NOT_LIVE`.

## Assumptions (where the brief is silent)

- **Clock:** "now" is always the **database** clock (`clock_timestamp()`), so
  two instances with skewed clocks still agree on whether a claim has expired
  or a deal is live. The 300-second webhook tolerance uses the app clock, as
  the brief says "your clock".
- **Second `payment.succeeded` (new `event_id`) on a PAID, NEEDS_REVIEW or REFUND_PENDING claim:**
  the claim and its stock don't change. The customer has been charged twice,
  so we must refund the extra charge, but must not undo a valid purchase. The
  event is stored with outcome `EXTRA_PAYMENT`, logged at WARN, and acknowledged with 200.
- **Unknown `claim_id`:** acknowledged with 200 and stored as `UNKNOWN_CLAIM`
  (WARN log). Claim ids are created before payment, so retrying can never make
  one appear; a non-2xx would only make the provider retry forever.
- **Unknown event `type`** with a valid signature: 200, stored as `UNKNOWN_TYPE`,
  so new provider event types don't cause a retry storm.
- A signed body that isn't valid JSON, or is missing fields: 400 `VALIDATION_FAILED`.
- `payment.failed` doesn't take the deal lock and never changes a claim. A
  later `payment.succeeded` is still applied, because the customer may pay
  again until expiry.
- A payment's lateness is judged by **when we process it**, not by its
  `occurred_at` (the brief says "processed after `expires_at`").
- **Check order** on `POST …/claims`: 401 → 403 → 400 (key) → 413/400 (body) → 404 (deal), then idempotency.
  Malformed path ids return 404.
- An `Idempotency-Key` older than 24 hours is deleted when it is next used, and
  then behaves like a new key.
- Number fields must be JSON integers: `"9900"` (a string) or `99.5` → 400.
- Error responses for unknown routes (and wrong methods) use the same
  `{"error": …}` shape, with code `NOT_FOUND`.
- Deals may be created with `starts_at` in the past. Deals are never deleted.
- A `REFUND_PENDING` claim doesn't block the customer from claiming again (it
  holds no units).

## What I'd do next with more time

- Stretch goals: `GET /v1/me/claims` with keyset pagination on `(created_at, id)`,
  a per-customer rate limit shared across instances (a Postgres or Redis
  counter), and `GET /v1/deals/{id}/claims` for admins.
- Publish `ClaimPaid`, `ClaimExpired` and `RefundRequested` events through a
  **transactional outbox** table written in the same transaction, then relay
  them to Kafka. Today REFUND_PENDING and EXTRA_PAYMENT only exist as rows and logs.
- Reduce round trips while the deal lock is held (one SQL function), and other
  hot-deal measures from DESIGN.md §7.
- Hikari pool and lock-wait metrics with alerts, and a JaCoCo coverage gate in CI.
- A cleanup job for idempotency keys older than 24 hours (today they are only
  removed when reused).

## AI tools

> ✍️ **Rewrite this section in your own words before submitting.** It must be honest about what you did.

- **Claude Code (Claude Opus)** — I used it to scaffold the project and draft
  the code, tests and these docs from the brief. I then reviewed every line and
  ran the test suite.
- **A suggestion I rejected:** *(fill in a real example from your own review,
  for instance a design you questioned and changed)*.
