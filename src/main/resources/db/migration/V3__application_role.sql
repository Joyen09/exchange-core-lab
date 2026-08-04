-- Privileges for the application role.
--
-- The role itself is created at cluster initialisation (docker/postgres/init/10-application-role.sh),
-- because it needs a password and a migration is the wrong place for one. This migration only grants,
-- and creates a password-less fallback so the grants below always have a subject — a database brought
-- up without the init script still ends up with a correct privilege set, just no way to log in as it.
--
-- Why the role exists at all: the owner is a superuser, and superusers bypass ACL checks entirely.
-- The UPDATE/DELETE revoke on postings in V2 is real in pg_class.relacl and completely inert against
-- the owner. Only a non-superuser role turns it into a control.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'exchange_core_app') THEN
        CREATE ROLE exchange_core_app;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO exchange_core_app;

-- UPDATE on accounts is required for `SELECT ... FOR UPDATE`, which is the only reason it is granted:
-- PostgreSQL treats the row lock as an update-shaped operation even when no column changes. The
-- application never updates an account row; the account row is a mutex (ADR-0005), not mutable state.
GRANT SELECT, INSERT, UPDATE ON accounts TO exchange_core_app;

GRANT SELECT, INSERT ON entries TO exchange_core_app;

-- Deliberately no UPDATE and no DELETE: postings are append-only, and this is the layer that holds
-- when the trigger is disabled.
GRANT SELECT, INSERT ON postings TO exchange_core_app;

GRANT SELECT ON service_metadata TO exchange_core_app;

-- Explicit rather than implied. If a future migration grants privileges table-wide, this line is
-- what a reviewer will look for.
REVOKE UPDATE, DELETE ON postings FROM exchange_core_app;
