-- Deals: stock_available is the single counter every claim, expiry and refund
-- updates while holding the deal row lock. The CHECKs are the last line of
-- defence: even a buggy code path cannot commit an oversell.
CREATE TABLE deals (
    id              uuid        PRIMARY KEY,
    restaurant_id   uuid        NOT NULL,
    title           text        NOT NULL CHECK (char_length(title) BETWEEN 1 AND 100),
    price_paise     bigint      NOT NULL CHECK (price_paise BETWEEN 1 AND 1000000),
    stock_total     integer     NOT NULL CHECK (stock_total BETWEEN 1 AND 10000),
    stock_available integer     NOT NULL,
    starts_at       timestamptz NOT NULL,
    ends_at         timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT deals_window_valid CHECK (starts_at < ends_at),
    CONSTRAINT deals_stock_in_range CHECK (stock_available BETWEEN 0 AND stock_total)
);

CREATE INDEX deals_restaurant_idx ON deals (restaurant_id);

CREATE TABLE claims (
    id           uuid        PRIMARY KEY,
    deal_id      uuid        NOT NULL REFERENCES deals (id),
    customer_id  uuid        NOT NULL,
    quantity     integer     NOT NULL CHECK (quantity BETWEEN 1 AND 3),
    amount_paise bigint      NOT NULL CHECK (amount_paise > 0),
    status       text        NOT NULL CHECK (status IN ('RESERVED', 'PAID', 'EXPIRED', 'NEEDS_REVIEW', 'REFUND_PENDING')),
    expires_at   timestamptz NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

-- Invariant 2: at most one active (unit-holding) claim per customer per deal.
-- The claim transaction already checks this under the deal lock; this index
-- makes it impossible to violate even if that check were removed.
CREATE UNIQUE INDEX claims_one_active_per_customer
    ON claims (deal_id, customer_id)
    WHERE status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW');

-- Lets the expiry job and the in-transaction release find due reservations
-- without scanning paid/expired history.
CREATE INDEX claims_reserved_by_expiry
    ON claims (expires_at, deal_id)
    WHERE status = 'RESERVED';

CREATE INDEX claims_reserved_by_deal
    ON claims (deal_id, expires_at)
    WHERE status = 'RESERVED';

-- Idempotency records are written in the SAME transaction as the claim they
-- describe, so a row exists if and only if the outcome was committed.
CREATE TABLE idempotency_keys (
    customer_id     uuid        NOT NULL,
    idempotency_key text        NOT NULL,
    deal_id         uuid        NOT NULL,
    quantity        integer     NOT NULL,
    response_status integer     NOT NULL,
    response_body   text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (customer_id, idempotency_key)
);

CREATE INDEX idempotency_keys_created_idx ON idempotency_keys (created_at);

-- Every webhook event we have accepted. The primary key on event_id is what
-- makes duplicate (and concurrently duplicated) deliveries a no-op.
CREATE TABLE payment_events (
    event_id     text        PRIMARY KEY,
    claim_id     uuid,
    type         text        NOT NULL,
    amount_paise bigint,
    occurred_at  timestamptz,
    outcome      text        NOT NULL,
    received_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX payment_events_claim_idx ON payment_events (claim_id);
