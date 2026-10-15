# Design

The whole design rests on one rule: **every claim, expiry and payment for a deal
takes the deal's row lock first (`SELECT … FROM deals WHERE id = ? FOR UPDATE`),
then touches claims.** PostgreSQL is the only place where the two instances
coordinate. Constraints in the schema are a backstop if a code path is ever wrong.

Key files: `claims/ClaimService.kt` (claim + idempotency),
`payments/Payments.kt` (webhook state machine), `claims/ExpiryJob.kt`,
`db/migration/V1__create_deals_and_claims.sql`.

## 1. Two customers on different instances claim the last unit at once

Each request runs one transaction (`ClaimService.kt:50`):

1. `INSERT INTO idempotency_keys … ON CONFLICT DO NOTHING` (`:118`). The keys
   differ, so both rows insert.
2. `SELECT price_paise, stock_available, … FROM deals WHERE id = ? FOR UPDATE` (`:58`).
   A gets the row lock. B's statement **blocks** on that lock in PostgreSQL.
3. A runs `releaseExpiredReservations` (`:69`, `:210`), checks the deal is live and
   that A holds no active claim, sees `stock_available = 1`, and runs
   `UPDATE deals SET stock_available = stock_available - 1` (`:83`).
   It then runs `INSERT INTO claims` (`:86`), stores the 201 response in
   `idempotency_keys` (`:99`), and commits.
4. The commit releases the lock. Under READ COMMITTED, B's `FOR UPDATE` re-reads
   the **latest committed version** of the row, so it sees `stock_available = 0`.
   B stores a 409 `SOLD_OUT` and commits.

A Kotlin `Mutex` or `synchronized` block only orders threads inside one JVM. A
and B run in two different processes (and could run on two machines), so a
lock in memory can't see the other instance. The state that needs protecting
lives in PostgreSQL, so the lock has to live there too. If a bug ever skipped
the lock, `CHECK (stock_available BETWEEN 0 AND stock_total)`
(migration line 15) would make the oversell's commit fail.

## 2. Commit, then crash before responding; or crash just before commit

The idempotency row and the claim are written **in the same transaction**, and
the row only commits together with its final response.

- **Crash after commit:** the retry's `INSERT … ON CONFLICT DO NOTHING`
  affects 0 rows, so the request reads the stored row (`ClaimService.kt:137`). The
  deal and quantity match, so it returns the stored status and body with
  `Idempotent-Replayed: true`. No second claim.
- **Crash before commit:** the process dies, its TCP connection closes, and
  PostgreSQL rolls the transaction back: no claim, no stock change, no key row.
  The retry runs as if it were the first request. If the process **hangs**
  instead of dying, `idle_in_transaction_session_timeout = 30s`
  (`Database.kt:57`) kills the session, so a key is never blocked for more than ~30s.
- **Retry while the first request is still running:** the second `INSERT`
  waits on the primary key until the first transaction finishes, then replays its
  result. We never persist an `IN_PROGRESS` row, so a crash can't leave a stuck
  key. `lock_timeout = 10s` (`:116`) turns a very long wait into 409 `REQUEST_IN_PROGRESS`.

## 3. A payment webhook and the expiry job hit the same claim at once

Both take the deal lock and then the claim lock (`Payments.kt:103-107`,
`ExpiryJob.kt:48`), so they run one after the other, never interleaved.
"Expired" means `clock_timestamp() >= expires_at` on the **database** clock, so
both instances share one clock. The job only touches rows where
`expires_at <= clock_timestamp()`. Let *q* be the claim's quantity and *S* the
stock before the claim was made.

| # | Who gets the lock first | Webhook sees | Final status | Final `stock_available` |
|---|---|---|---|---|
| a | Webhook, before `expires_at` | RESERVED, in time | `PAID` (or `NEEDS_REVIEW` if the amount differs). The job later finds nothing to expire | S − q (units kept) |
| b | Webhook, after `expires_at`, job not yet run | RESERVED, late | `REFUND_PENDING`; the webhook returns q units (`Payments.kt:119`). The job finds nothing | S |
| c | Job (after `expires_at`), then webhook | EXPIRED | `EXPIRED` → `REFUND_PENDING`; units **not** returned again (`:131`) | S |
| d | Job arrives while the webhook holds the lock | — | `SKIP LOCKED` skips the deal, so the result is (a) or (b) | as (a)/(b) |
| e | Webhook arrives while the job holds the lock | waits, then EXPIRED | same as (c) | S |

A claim request on the same deal can play the job's role (it calls
`releaseExpiredReservations` under the same lock), with the same outcomes as
(c) and (e). In every row the claim ends in exactly one of `PAID` (units kept)
or `REFUND_PENDING` (units returned once). `PaymentExpiryRaceTest` fires 150
payments within ±300 ms of expiry, with both instances sweeping every 20 ms, and
checks exactly this.

## 4. Webhook signatures

- **Raw body:** the provider signed bytes, not a JSON object. Parsing and
  re-serialising changes whitespace, key order, number formatting and escapes,
  so a correct signature would fail. It's also unsafe: the parser forgives
  duplicate keys and unknown fields, so two different byte strings can parse to
  the same object. We verify the exact bytes we received, before parsing them
  (`Payments.kt:163`).
- **Timestamp:** `t` is inside the signed message, so an attacker can't change it.
  Rejecting `|now − t| > 300s` (`WebhookSignature.kt:19`) stops someone who
  captured a valid request from replaying it later. Within the window, the
  `event_id` primary key makes a replay a no-op.
- **Constant-time compare:** `MessageDigest.isEqual` (`:23`) takes the same time
  however many leading bytes match. A normal `==` returns at the first wrong
  byte, so an attacker could time responses and forge a signature byte by byte.

## 5. Isolation level

**READ COMMITTED** (`Database.kt:28`), plus explicit row locks and unique
constraints. That is enough because every decision (stock check, active-claim
check, payment versus expiry) is made **after** taking the deal row lock. Under
READ COMMITTED each statement sees the latest committed data, so once we hold
the lock, what we read can't change under us. SERIALIZABLE would also be
correct, but on a hot deal it would abort most transactions with serialization
failures that the client would have to retry.

The anomaly without the lock is a **lost update**. Both transactions run
`SELECT stock_available` and read 1, then both write 0 (or each runs
`stock - 1` after its own check). Two claims succeed for one unit. Checking "no
active claim" and then inserting has the same problem (write skew) without the
lock or the partial unique index.

## 6. The constraint doing the most work

The **primary key on `idempotency_keys (customer_id, idempotency_key)`**
(migration line 59). It decides which of N concurrent retries runs, makes the
others wait, and holds the replayed response. If it were dropped, `ON CONFLICT`
would have no unique index to arbitrate on and every claim would fail. If someone
then removed the `ON CONFLICT` as a "fix", 20 concurrent retries would each run
the claim. One would get 201 and the rest 409 `ALREADY_CLAIMED` instead of a
replay. Across different deals, one key would create several claims, and the
422 for a reused key would disappear.

Close behind it: `claims_one_active_per_customer` (a partial unique index, line 35)
and `deals_stock_in_range` (line 15). Today they only back up the deal lock, but
without them a future change that forgot the lock would silently oversell instead
of failing.

## 7. A deal goes viral: 100× claim traffic

**What fails first:** the single deal row lock. Every claim on a deal queues on
it, and each one holds it for about 7 statements' worth of round trips. While
they wait, requests also **hold pooled connections** (20 per instance). The
pools drain, unrelated endpoints start timing out (`connectionTimeout`), and
PostgreSQL `max_connections` becomes the next ceiling.

**What I'd change, roughly in order:**
1. **Fail fast once sold out:** the claim still has to store its 409, but we can
   check `stock_available = 0` without the lock and skip queueing behind it.
2. **Fewer round trips under the lock:** run the whole claim as one SQL function
   or CTE, so the lock is held for one round trip instead of seven.
3. **Split the stock** into k bucket rows per deal and lock a random bucket with
   `FOR UPDATE SKIP LOCKED`, so k claims can proceed at the same time.
4. **Admission control** in front of the database: a per-customer rate limit, and
   an atomic Redis counter as a gate (PostgreSQL stays the source of truth).
5. **Infrastructure:** PgBouncer, more instances, and alerts on lock wait time
   and pool saturation (claim and webhook outcome counters are already in `/metrics`).
