#!/bin/sh
# Creates the login role the application connects as.
#
# This runs once, during cluster initialisation, so the role exists before anything tries to use it.
# It cannot live in a Flyway migration, because migrations run as the owner *after* the application's
# connection pool has already started — and because a migration carrying a password would put one in
# the repository. The matching grants are in V3__application_role.sql, which runs as the owner.
#
# The point of the separate role is that the owner is a superuser and superusers bypass ACL checks:
# privileges revoked on `postings` are real in the catalogue but inert against the owner. Only a
# non-superuser role makes the revoke an actual control rather than documentation.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
	DO \$\$
	BEGIN
	    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'exchange_core_app') THEN
	        CREATE ROLE exchange_core_app LOGIN PASSWORD '${APP_DB_PASSWORD:-local_dev_only}';
	    END IF;
	END
	\$\$;
EOSQL
