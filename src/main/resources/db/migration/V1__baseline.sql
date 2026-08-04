-- Phase 0 baseline.
--
-- Deliberately minimal: it exists to prove the Flyway pipeline runs end to end
-- (container start -> migrate -> healthy). The ledger schema (accounts / postings /
-- entries) belongs to Phase 1 and is not pulled forward, so that each phase's
-- acceptance criteria stay independently verifiable.

CREATE TABLE service_metadata (
    meta_key   TEXT PRIMARY KEY,
    meta_value TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE service_metadata IS
    'Phase 0 baseline table: service identity and schema provenance. Not business data.';

INSERT INTO service_metadata (meta_key, meta_value) VALUES
    ('service_name', 'exchange-core-lab'),
    ('schema_baseline_phase', '0'),
    ('exchange_environment', 'binance-spot-testnet');
