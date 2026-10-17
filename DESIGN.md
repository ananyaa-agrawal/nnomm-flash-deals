# Design

One rule holds the design together: **every claim, expiry and payment on a deal
locks that deal's row first (`SELECT … FROM deals WHERE id = ? FOR UPDATE`), and
only then touches its claims.** The two instances share nothing except
PostgreSQL, so that is where all coordination happens. Schema constraints back
the code up if it is ever wrong. Paths below are under
`src/main/kotlin/com/nnomm/flashdeals/`. "Migration" means
`db/migration/V1__create_deals_and_claims.sql`.

## 1. Two customers, two instances, one last unit

Each claim is one transaction, `ClaimService.claim` (`claims/ClaimService.kt:50`):

1. `INSERT INTO idempotency_keys … ON CONFLICT DO NOTHING` (`:120`). The two
   customers have different keys, so both rows go in.
2. `SELECT … FROM deals WHERE id = ? FOR UPDATE` (`:60`). Customer A gets the
   row lock. B's statement blocks inside PostgreSQL.
3. A returns any lapsed reservations to stock (`:69`) and checks the deal is live
   (`:74`) and that A has no active claim (`:76`). A sees 1 unit, runs
   `UPDATE deals SET stock_available = stock_available - 1` (`:83`) and
   `INSERT INTO claims` (`:86`), stores the 201 body against the key (`:99`), and
   commits.
4. The commit releases the lock. Under READ COMMITTED, B's `FOR UPDATE` re-reads
   the newest committed row, so B sees 0 units. B stores 409 `SOLD_OUT` and commits.

A Kotlin `Mutex` or `synchronized` only orders threads inside one JVM. A and B
run in different processes, possibly on different machines, so an in-memory
lock can't see the other one. The shared state is in PostgreSQL, so the lock
has to be there too. As a backstop, `CHECK (stock_available BETWEEN 0 AND stock_total)`
(migration line 15) makes any oversell fail at commit.

## 2. Crash after commit, or just before it

The key row, the claim and the stored response are written in **one
transaction**, so they become durable together or not at all.

- **Crash after commit:** on the retry, `ON CONFLICT DO NOTHING` inserts nothing.
  We read the stored row (`:138`), see the same deal and quantity, and return the
  stored status and body with `Idempotent-Replayed: true`. No second claim.
- **Crash before commit:** the dead process's connection closes and PostgreSQL
  rolls everything back: no claim, no stock change, no key row. The retry runs
  as a fresh request. If the process **hangs** instead of dying,
  `idle_in_transaction_session_timeout = 30s` (`db/Database.kt:57`) ends the
  session, so a key is blocked for 30s at most. The brief allows 60.
- **Retry while the first request is still running:** its `INSERT` waits on the
  primary key until the first transaction ends, then replays the result. I never
  store an "in progress" row, so a crash can't leave a key stuck.
  `lock_timeout = 10s` (`:116`) turns a long wait into 409 `REQUEST_IN_PROGRESS`.

## 3. Payment webhook versus expiry job, same millisecond

Both take the deal lock and then the claim lock: `payments/Payments.kt:103-107`
for the webhook, and `claims/ExpiryJob.kt:48` (with `SKIP LOCKED`) for the job.
So they run one after the other, never interleaved. Both use the **database**
clock (`clock_timestamp()`), so both instances agree on what "expired" means.
The job only touches rows where `expires_at <= clock_timestamp()`. Below, S is
the stock before the claim and q is its quantity.

| Lock order | Webhook sees | Final status | Final stock |
|---|---|---|---|
| Webhook first, before `expires_at` | RESERVED, in time | `PAID` (`NEEDS_REVIEW` if the amount differs). The job then has nothing to do | S − q |
| Webhook first, after `expires_at`, job not run yet | RESERVED, late | `REFUND_PENDING`; the webhook returns q (`Payments.kt:119`) | S |
| Job first, then webhook | EXPIRED | `REFUND_PENDING`; units **not** returned again (`:131`) | S |
| Job tries while the webhook holds the lock | — | `SKIP LOCKED` skips the deal, so it ends like row 1 or 2 | S − q or S |
| Webhook arrives while the job holds the lock | waits, then EXPIRED | same as row 3 | S |

A new claim on the same deal can play the job's part, because it calls the same
`releaseExpiredReservations` (`ClaimService.kt:210`) under the same lock.
Either way, a claim ends either `PAID` with its units held or `REFUND_PENDING`
with them returned once. `PaymentExpiryRaceTest` checks this by sending 150
payments within ±300 ms of expiry while both instances sweep every 20 ms.

## 4. Webhook signatures

- **Raw body:** the provider signs bytes, not a JSON object. Parse-and-reserialise
  changes whitespace, key order and number formats, so genuine requests would
  fail. Worse, a lenient parser can map different byte strings to the same object.
  I verify the exact received bytes before parsing (`Payments.kt:163`).
- **Timestamp:** `t` is part of the signed message, so it can't be changed.
  Rejecting `|now − t| > 300s` (`payments/WebhookSignature.kt:19`) stops someone
  replaying a captured request later. Inside the window, the `event_id` primary
  key (`Payments.kt:80`) makes a replay a no-op.
- **Constant time:** `MessageDigest.isEqual` (`:23`) takes the same time no matter
  where the bytes differ. An ordinary comparison stops at the first wrong byte,
  so an attacker could time responses and guess the signature byte by byte.

## 5. Isolation level

I use **READ COMMITTED** (`db/Database.kt:28`), with explicit row locks and unique
constraints. It is enough because every decision (stock, active claim, payment
versus expiry) is made **after** taking the deal lock. In READ COMMITTED each
statement sees the latest committed data, and nobody else can change the locked
row until I commit. SERIALIZABLE would also be correct, but on a hot deal most
transactions would abort with serialization failures and need retrying.

Without the lock you get a **lost update**: two transactions both read
`stock_available = 1`, both decide there is room, and both write 0, so two
claims share one unit. "Check there is no active claim, then insert" has the
same race (write skew).

## 6. The constraint doing the most work

The **primary key on `idempotency_keys (customer_id, idempotency_key)`**
(migration line 59). It decides which of N concurrent retries actually runs,
makes the others wait, and holds the answer they replay. If it is dropped, the
`ON CONFLICT` clause has no index to work against, so every claim errors. If
someone "fixes" that by deleting the clause, 20 concurrent retries each run the
claim: one gets 201, the rest get `ALREADY_CLAIMED` instead of a replay, and the
same key could create claims on different deals.

Close behind are the partial unique index `claims_one_active_per_customer` (line
35) and `deals_stock_in_range` (line 15). Today they only back up the lock, but
without them, a future change that forgot the lock would oversell silently
instead of failing loudly.

## 7. A deal goes viral: 100× traffic

**What fails first** is the deal's row lock. Every claim on that deal queues
behind it, and each holds it for about seven SQL round trips. While waiting, each
request also **holds a pooled connection** (20 per instance). The pool runs dry,
even unrelated endpoints start timing out, and PostgreSQL's `max_connections`
becomes the next wall.

**What I'd change, in order:**
1. Return `SOLD_OUT` fast: read `stock_available` without the lock first, and
   only queue for the lock if units remain.
2. Hold the lock for one round trip instead of seven by moving the claim into a
   single SQL function.
3. Split stock across k bucket rows per deal, and lock a random bucket with
   `FOR UPDATE SKIP LOCKED`, so k claims can run in parallel.
4. Add admission control in front of the database: the per-customer rate limit,
   and an atomic Redis counter as a gate, with PostgreSQL still the source of truth.
5. PgBouncer, more instances, and alerts on lock waits and pool saturation.
