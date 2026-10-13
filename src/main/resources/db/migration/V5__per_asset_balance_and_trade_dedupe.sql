-- Exchange adapter groundwork (Phase 3).
--
-- Decisions recorded in:
--   docs/adr/0004-zero-sum-enforcement.md  — the per-asset correction, and why it is a strengthening
--   docs/adr/0011-reconnect-recovery.md    — why (symbol, trade_id) is the de-duplication key
--
-- Three changes: the zero-sum invariant is restated per asset, trades get a de-duplication table, and
-- orders get a marker for "recovery is in progress, do not judge this one yet".


-- ---------------------------------------------------------------------------------------------
-- 1. The zero-sum invariant, restated per asset.
--
-- V2 summed every posting in an entry regardless of asset:
--
--     SELECT COALESCE(SUM(amount), 0) FROM postings WHERE entry_id = target_entry
--
-- That was indistinguishable from correct for two phases, because every entry the system could
-- produce touched one asset — a deposit moves EXTERNAL(USDT) to AVAILABLE(USDT), an order lock
-- moves AVAILABLE(USDT) to LOCKED(USDT). The global sum and the per-asset sum were the same number.
--
-- A trade is the first entry that is inherently cross-asset. A BTCUSDT buy moves USDT out and BTC
-- in, and SUM(amount) over those postings adds a quantity of bitcoin to a quantity of dollars. The
-- result is not merely non-zero, it is meaningless.
--
-- This is a STRENGTHENING, not a relaxation. For a single-asset entry the two statements are
-- identical. For a cross-asset entry the old rule could be satisfied by nonsense — BTC +1, USDT -1
-- sums to zero — and the new one cannot. Nothing that V2 rejected is now accepted.
--
-- The asset lives on accounts, not postings, so the check has to join. That join is the only cost,
-- and postings_account_id_idx already exists for it.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION assert_entry_balanced() RETURNS trigger AS $$
DECLARE
    target_entry UUID;
    offending    RECORD;
BEGIN
    target_entry := CASE TG_OP WHEN 'DELETE' THEN OLD.entry_id ELSE NEW.entry_id END;

    SELECT a.asset, SUM(p.amount) AS total
      INTO offending
      FROM postings p
      JOIN accounts a ON a.id = p.account_id
     WHERE p.entry_id = target_entry
     GROUP BY a.asset
    HAVING SUM(p.amount) <> 0
     LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION 'entry % is unbalanced in %: postings sum to %',
            target_entry, offending.asset, offending.total
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- The trigger itself is unchanged and is deliberately not recreated: CREATE OR REPLACE FUNCTION
-- swaps the body underneath the existing constraint trigger, so the deferred-to-commit timing, the
-- UPDATE/DELETE coverage and the trigger's name all survive. Dropping and recreating it would risk
-- losing DEFERRABLE INITIALLY DEFERRED, which is the property ADR-0004 is mostly about.


-- ---------------------------------------------------------------------------------------------
-- 2. Re-validate every entry that already exists.
--
-- A migration that changes an invariant and does not check the existing rows is asserting something
-- about data it has not looked at. If any entry fails here the migration aborts, and that is the
-- intended outcome: it would mean Phase 1 or Phase 2 wrote an entry that is unbalanced within an
-- asset, which is a real data defect to be investigated rather than migrated past.
--
-- The deferred trigger does not fire for rows that are already committed, so this has to be an
-- explicit pass.
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    broken      INT;
    sample      TEXT;
    checked     INT;
BEGIN
    SELECT count(*) INTO checked FROM entries;

    SELECT count(*), COALESCE(string_agg(DISTINCT detail, '; '), '')
      INTO broken, sample
      FROM (
          SELECT p.entry_id,
                 format('entry %s asset %s sums to %s', p.entry_id, a.asset, SUM(p.amount)) AS detail
            FROM postings p
            JOIN accounts a ON a.id = p.account_id
           GROUP BY p.entry_id, a.asset
          HAVING SUM(p.amount) <> 0
      ) AS failures;

    IF broken > 0 THEN
        RAISE EXCEPTION
            'V5 aborted: % existing entry/asset combinations are unbalanced under the per-asset rule. %',
            broken, sample
            USING ERRCODE = '23514';
    END IF;

    RAISE NOTICE 'V5: % existing entries re-validated under the per-asset rule, all balanced', checked;
END;
$$;


-- ---------------------------------------------------------------------------------------------
-- 3. Trade de-duplication.
--
-- A fill can reach this system twice by two different routes: the live executionReport stream and
-- the REST backfill that runs after every reconnect. ADR-0011 chooses to let that happen rather
-- than prevent it — overlapping is cheap, missing a fill is permanent — so the de-duplication here
-- is load-bearing rather than defensive.
--
-- The key is the venue's own trade id, because the venue is the only party that knows whether two
-- reports describe the same fill. A key computed from quantity, price and timestamp would merge two
-- genuinely distinct fills that happen to be identical, which is not unusual in a liquid market.
--
-- symbol is part of the key because Binance documents trade_id as unique per symbol, not globally
-- (docs/vendor/binance-citations.md). A global key would be a silent collision between markets.
--
-- Note for the application: executionReport sets the trade id to -1 when the event is not a fill —
-- a NEW or CANCELED report — so rows must only be written for execution type TRADE. Inserting
-- (symbol, -1) would let the first non-trade event claim a key that every later one collides with.
-- The CHECK below makes that mistake fail loudly instead of silently swallowing events.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE processed_trades (
    symbol       VARCHAR(20)    NOT NULL,
    trade_id     BIGINT         NOT NULL,
    order_id     UUID           NOT NULL REFERENCES orders (id),
    quantity     NUMERIC(36,18) NOT NULL,
    price        NUMERIC(36,18) NOT NULL,
    commission   NUMERIC(36,18),
    commission_asset VARCHAR(20),
    occurred_at  TIMESTAMPTZ    NOT NULL,
    recorded_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT pk_processed_trades      PRIMARY KEY (symbol, trade_id),
    CONSTRAINT ck_processed_trades_id   CHECK (trade_id >= 0),
    CONSTRAINT ck_processed_trades_qty  CHECK (quantity > 0),
    CONSTRAINT ck_processed_trades_px   CHECK (price > 0),
    CONSTRAINT ck_processed_trades_fee  CHECK ((commission IS NULL) = (commission_asset IS NULL))
);

COMMENT ON TABLE processed_trades IS
    'One row per venue fill, keyed by the venue''s own (symbol, trade_id). Written on first sight '
    'whether that arrives by WebSocket or by reconnect backfill; the primary key is what makes the '
    'second arrival a no-op. See ADR-0011.';

CREATE INDEX processed_trades_order_idx ON processed_trades (order_id);

-- Append-only, for the same reason postings are: a de-duplication ledger that can be edited is a
-- de-duplication ledger that can be made to forget.
CREATE OR REPLACE FUNCTION reject_processed_trade_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'processed_trades is append-only: % is not permitted', TG_OP
        USING ERRCODE = '0A000';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER processed_trades_append_only
    BEFORE UPDATE OR DELETE ON processed_trades
    FOR EACH ROW EXECUTE FUNCTION reject_processed_trade_mutation();


-- ---------------------------------------------------------------------------------------------
-- 4. Recovery marker.
--
-- While a reconnect backfill is assembling an order's true state, that state is legitimately
-- incomplete, and Phase 4's reconciler must not report a difference against it.
--
-- A column rather than an OrderStatus, deliberately. "Recovering" is not a stage of an order's life
-- — the order is still SUBMITTED or PARTIALLY_FILLED throughout — and adding it to the enum would
-- pollute the 64-cell transition table and the terminal-state logic with something that is not a
-- state at all.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE orders ADD COLUMN recovering_since TIMESTAMPTZ;

COMMENT ON COLUMN orders.recovering_since IS
    'Set when a reconnect backfill starts touching this order, cleared when it finishes. Non-null '
    'means the state is still being assembled and must not be reconciled against. A stale value is '
    'itself a signal: a backfill that died halfway leaves one behind.';

-- Partial index: the interesting set is tiny and transient, and a full index on a mostly-null
-- column would cost writes on every transition for nothing.
CREATE INDEX orders_recovering_idx ON orders (recovering_since) WHERE recovering_since IS NOT NULL;


-- ---------------------------------------------------------------------------------------------
-- 5. Privileges.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON processed_trades TO exchange_core_app;
REVOKE UPDATE, DELETE ON processed_trades FROM exchange_core_app;
