-- Double-entry ledger (SPEC §4 Phase 1).
--
-- Design decisions and their costs are recorded in:
--   docs/adr/0004-zero-sum-enforcement.md      — why the invariant lives here and not in Java
--   docs/adr/0005-balance-concurrency-control.md — why balances are derived and how writes serialise
--
-- Money is NUMERIC(36,18) everywhere. No column in this file may ever become a float.

CREATE TABLE accounts (
    id         UUID PRIMARY KEY,
    owner_id   TEXT        NOT NULL,
    asset      TEXT        NOT NULL,
    type       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT accounts_type_known CHECK (type IN ('AVAILABLE', 'LOCKED', 'EXTERNAL', 'FEE')),
    CONSTRAINT accounts_identity_unique UNIQUE (owner_id, asset, type)
);

COMMENT ON TABLE accounts IS
    'One row per (owner, asset, purpose). The row is also the mutex for the account''s derived '
    'balance — see ADR-0005; it is locked FOR UPDATE by writers but never itself updated.';

CREATE TABLE entries (
    id              UUID PRIMARY KEY,
    idempotency_key TEXT        NOT NULL,
    kind            TEXT        NOT NULL,
    ref_id          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT entries_idempotency_key_unique UNIQUE (idempotency_key)
);

COMMENT ON TABLE entries IS
    'A single balanced movement of value. The unique idempotency_key is what makes a replayed '
    'write return the original entry instead of creating a second one.';

CREATE TABLE postings (
    id         UUID           PRIMARY KEY,
    entry_id   UUID           NOT NULL REFERENCES entries (id),
    account_id UUID           NOT NULL REFERENCES accounts (id),
    amount     NUMERIC(36, 18) NOT NULL,
    created_at TIMESTAMPTZ    NOT NULL DEFAULT now()
);

COMMENT ON TABLE postings IS
    'Append-only. Balances are derived by summing this table; there is deliberately no snapshot '
    'column, so this is the only place an account balance exists.';

-- Required by the zero-sum trigger (per entry) and the balance aggregate (per account). Without
-- them both degrade to sequential scans and the concurrency tests measure the wrong thing.
CREATE INDEX postings_entry_id_idx ON postings (entry_id);
CREATE INDEX postings_account_id_idx ON postings (account_id);


-- ---------------------------------------------------------------------------------------------
-- Invariant 1: every entry's postings sum to zero.
--
-- Deferred to commit because an entry is built one posting at a time, so the invariant is
-- legitimately false in between. A row-level CHECK cannot express this: it spans rows.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION assert_entry_balanced() RETURNS trigger AS $$
DECLARE
    target_entry UUID;
    total        NUMERIC(36, 18);
BEGIN
    target_entry := CASE TG_OP WHEN 'DELETE' THEN OLD.entry_id ELSE NEW.entry_id END;

    SELECT COALESCE(SUM(amount), 0) INTO total FROM postings WHERE entry_id = target_entry;

    IF total <> 0 THEN
        RAISE EXCEPTION 'entry % is unbalanced: postings sum to %', target_entry, total
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- UPDATE and DELETE are covered even though the append-only trigger below forbids both, so that
-- dropping that trigger does not silently take this check with it.
CREATE CONSTRAINT TRIGGER postings_entry_balanced
    AFTER INSERT OR UPDATE OR DELETE ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_entry_balanced();


-- ---------------------------------------------------------------------------------------------
-- Invariant 2: an entry has at least two postings.
--
-- The trigger above fires per posting row, so an entry with NO postings fires it zero times and
-- balances vacuously — a silent hole. Two is the floor for double-entry: a single posting can
-- only balance if its amount is zero, which is meaningless by another route.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION assert_entry_has_postings() RETURNS trigger AS $$
DECLARE
    posting_count INTEGER;
BEGIN
    SELECT count(*) INTO posting_count FROM postings WHERE entry_id = NEW.id;

    IF posting_count < 2 THEN
        RAISE EXCEPTION 'entry % has % posting(s); a double-entry entry needs at least 2',
            NEW.id, posting_count
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER entries_have_postings
    AFTER INSERT ON entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_entry_has_postings();


-- ---------------------------------------------------------------------------------------------
-- Invariant 3: postings are append-only.
--
-- Zero-sum is checked once, at the commit that creates the entry. Any later UPDATE or DELETE
-- could break it and would never be re-checked. Append-only is what makes a one-time check sound.
-- Unlike the two above this fires immediately: there is no legitimate intermediate state.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION reject_posting_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'postings are append-only; % on posting % is not permitted', TG_OP, OLD.id
        USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER postings_append_only
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION reject_posting_mutation();

-- Second layer, independent of the trigger: privileges survive ALTER TABLE ... DISABLE TRIGGER.
-- In a deployment with a separate application role this targets that role; here the migration
-- runs as the application role itself, so it targets current_user.
DO $$
BEGIN
    EXECUTE format('REVOKE UPDATE, DELETE ON postings FROM %I', current_user);
END;
$$;
