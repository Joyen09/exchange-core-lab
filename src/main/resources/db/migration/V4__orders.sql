-- Order lifecycle: state machine, idempotency, transactional outbox (Phase 2).
--
-- Decisions recorded in:
--   docs/adr/0006-transactional-outbox-by-polling.md      — why polling rather than CDC
--   docs/adr/0007-two-layer-idempotency.md                — why HTTP and domain idempotency both exist
--   docs/adr/0008-order-events-as-the-source-of-truth.md  — why order_events is the source of truth
--
-- Money is NUMERIC(36,18) and time is TIMESTAMPTZ throughout (SPEC §5.1, §5.2).


-- ---------------------------------------------------------------------------------------------
-- Tradable symbols.
--
-- A table rather than a constant, because locking funds needs the quote asset of a symbol and
-- guessing it from the string is wrong: ETHBTC quotes in BTC, and BTC is also the base of other
-- symbols, so suffix matching is ambiguous. Phase 3 can refresh this from the venue's exchangeInfo;
-- an unknown symbol is rejected rather than inferred.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE symbols (
    symbol       VARCHAR(20) PRIMARY KEY,
    base_asset   VARCHAR(16) NOT NULL,
    quote_asset  VARCHAR(16) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_symbols_assets_differ CHECK (base_asset <> quote_asset)
);

INSERT INTO symbols (symbol, base_asset, quote_asset) VALUES
    ('BTCUSDT', 'BTC', 'USDT'),
    ('ETHUSDT', 'ETH', 'USDT'),
    ('ETHBTC',  'ETH', 'BTC');


-- ---------------------------------------------------------------------------------------------
-- Orders: a projection of order_events, not the source of truth (ADR-0008).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE orders (
    id               UUID PRIMARY KEY,
    -- Single-tenant for now; the column exists so that Phase 4 risk limits and the optional Phase 6
    -- wallet work do not have to backfill a table that already holds data. Not exposed by the API.
    owner_id         VARCHAR(64)    NOT NULL DEFAULT 'local',
    client_order_id  VARCHAR(64)    NOT NULL,
    symbol           VARCHAR(20)    NOT NULL REFERENCES symbols (symbol),
    side             VARCHAR(4)     NOT NULL,
    type             VARCHAR(10)    NOT NULL,
    time_in_force    VARCHAR(3),
    quantity         NUMERIC(36,18) NOT NULL,
    price            NUMERIC(36,18),
    filled_quantity  NUMERIC(36,18) NOT NULL DEFAULT 0,
    avg_fill_price   NUMERIC(36,18),
    status           VARCHAR(20)    NOT NULL,
    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL,
    updated_at       TIMESTAMPTZ    NOT NULL,

    -- Uniqueness is enforced here, not by a read-then-write check in the application, which would
    -- be a race under concurrency (ADR-0007).
    --
    -- Scoped to the owner, not global. A client order id is a name the *caller* chose, so two callers
    -- naming their own orders "1" have not collided — and a global constraint would make one of them
    -- fail for a reason it could neither predict nor see. That is the whole purpose of the owner_id
    -- column above: scoping it now costs nothing, while widening a global constraint later means
    -- deciding what to do about the rows that already collided.
    CONSTRAINT uq_orders_client_order_id UNIQUE (owner_id, client_order_id),
    CONSTRAINT ck_orders_side            CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_orders_type            CHECK (type IN ('LIMIT', 'MARKET')),
    CONSTRAINT ck_orders_tif             CHECK (time_in_force IS NULL OR time_in_force IN ('GTC', 'IOC', 'FOK')),
    CONSTRAINT ck_orders_status          CHECK (status IN (
        'PENDING', 'SUBMITTED', 'PARTIALLY_FILLED', 'CANCELING',
        'FILLED', 'CANCELED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT ck_orders_filled_le_qty   CHECK (filled_quantity <= quantity),
    CONSTRAINT ck_orders_qty_positive    CHECK (quantity > 0),
    CONSTRAINT ck_orders_price_positive  CHECK (price IS NULL OR price > 0)
);

CREATE INDEX orders_status_created_idx ON orders (status, created_at DESC);
CREATE INDEX orders_symbol_created_idx ON orders (symbol, created_at DESC);
-- Supports keyset pagination; the sort key must be unique, hence (created_at, id).
CREATE INDEX orders_keyset_idx ON orders (created_at DESC, id DESC);


-- ---------------------------------------------------------------------------------------------
-- Order events: append-only, and the actual source of truth.
--
-- The UNIQUE (order_id, sequence_no) constraint is the concurrency control: two transactions
-- advancing the same order both compute the same next sequence_no, and the second one fails on the
-- constraint rather than producing a forked event stream.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE order_events (
    id          BIGSERIAL PRIMARY KEY,
    order_id    UUID        NOT NULL REFERENCES orders (id),
    sequence_no BIGINT      NOT NULL,
    event_type  VARCHAR(40) NOT NULL,
    from_status VARCHAR(20),
    to_status   VARCHAR(20) NOT NULL,
    payload     JSONB       NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_order_events_seq       UNIQUE (order_id, sequence_no),
    CONSTRAINT ck_order_events_seq_pos   CHECK (sequence_no >= 1),
    CONSTRAINT ck_order_events_type      CHECK (event_type IN (
        'ORDER_CREATED', 'ORDER_SUBMITTED', 'ORDER_PARTIALLY_FILLED', 'ORDER_FILLED',
        'ORDER_CANCEL_REQUESTED', 'ORDER_CANCELED', 'ORDER_REJECTED', 'ORDER_EXPIRED'))
);

CREATE INDEX order_events_order_seq_idx ON order_events (order_id, sequence_no);

-- Append-only, enforced the same way postings are (ADR-0004): an immediate trigger that covers every
-- role, plus revoked privileges that survive the trigger being disabled. Rebuilding a projection
-- from an event log is only meaningful if the log cannot be rewritten.
CREATE OR REPLACE FUNCTION reject_order_event_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'order_events is append-only; % on event % is not permitted', TG_OP, OLD.id
        USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER order_events_append_only
    BEFORE UPDATE OR DELETE ON order_events
    FOR EACH ROW EXECUTE FUNCTION reject_order_event_mutation();


-- ---------------------------------------------------------------------------------------------
-- HTTP request de-duplication. A different mechanism from client_order_id, at a different layer
-- (ADR-0007).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    key           VARCHAR(255) PRIMARY KEY,
    request_hash  CHAR(64)    NOT NULL,
    http_status   INT,
    -- TEXT, not JSONB, and the distinction matters. This column is a recording of a response that has
    -- already been sent: a replay has to return the same bytes, and jsonb normalises whitespace and
    -- discards key order, so a round trip through it returns an equivalent document rather than the same
    -- one. Nothing ever queries inside this value, so jsonb's indexing buys nothing to offset that. The
    -- replay test in §7.2 asserts byte equality and fails outright against JSONB.
    response_body TEXT,
    resource_id   UUID,
    created_at    TIMESTAMPTZ NOT NULL,
    completed_at  TIMESTAMPTZ,
    expires_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX idempotency_keys_expires_idx ON idempotency_keys (expires_at);


-- ---------------------------------------------------------------------------------------------
-- Transactional outbox.
--
-- Terminal failure is a dead_at timestamp rather than an attempts threshold: the publisher's partial
-- index can then exclude dead rows outright, and "how many times did this fail" stays separate from
-- "this will never be sent", which one column cannot mean at once.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE outbox (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  VARCHAR(40) NOT NULL,
    aggregate_id    UUID        NOT NULL,
    event_id        UUID        NOT NULL UNIQUE,
    event_type      VARCHAR(40) NOT NULL,
    payload         JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    published_at    TIMESTAMPTZ,
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error      TEXT,
    dead_at         TIMESTAMPTZ
);

-- Partial index: the unsent set stays small even as the table grows, because published rows are kept
-- for forensics rather than deleted (retention job, default 7 days).
CREATE INDEX outbox_unpublished_idx ON outbox (next_attempt_at, id)
    WHERE published_at IS NULL AND dead_at IS NULL;


-- ---------------------------------------------------------------------------------------------
-- Consumer-side de-duplication, for the minimal verification consumer (§5.3). It exists to prove
-- at-least-once plus de-duplication adds up to exactly-once effects; it has no business side effects.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    aggregate_id UUID        NOT NULL,
    sequence_no  BIGINT,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX processed_events_processed_idx ON processed_events (processed_at);


-- ---------------------------------------------------------------------------------------------
-- Privileges for the application role (V3). Explicit per table: a blanket grant would hand out the
-- UPDATE and DELETE that order_events must not have.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT ON symbols TO exchange_core_app;

-- UPDATE on orders covers both the projection write and `SELECT ... FOR UPDATE`, which PostgreSQL
-- treats as update-shaped even when no column changes.
GRANT SELECT, INSERT, UPDATE ON orders TO exchange_core_app;

GRANT SELECT, INSERT ON order_events TO exchange_core_app;
GRANT USAGE, SELECT ON SEQUENCE order_events_id_seq TO exchange_core_app;
REVOKE UPDATE, DELETE ON order_events FROM exchange_core_app;

-- DELETE is needed here: expired keys are removed by the retention job.
GRANT SELECT, INSERT, UPDATE, DELETE ON idempotency_keys TO exchange_core_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON outbox TO exchange_core_app;
GRANT USAGE, SELECT ON SEQUENCE outbox_id_seq TO exchange_core_app;

GRANT SELECT, INSERT, DELETE ON processed_events TO exchange_core_app;
