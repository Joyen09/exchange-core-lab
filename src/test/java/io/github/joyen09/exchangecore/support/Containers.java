package io.github.joyen09.exchangecore.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * One PostgreSQL and one Redpanda for the whole test JVM, started on first use.
 *
 * <p>Shared between the Spring integration tests and the jqwik properties, which cannot use a Spring
 * context. Lazily per resource, so the tests that need no broker do not pay for one.
 *
 * <p>The container's credentials deliberately match the ones in {@code application.yml}, because Flyway
 * runs as the owner with credentials from properties while the application connects separately. Making
 * them agree here is what lets the real configuration be exercised rather than overridden.
 */
public final class Containers {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("exchange_core_lab")
            .withUsername("exchange_core")
            .withPassword("local_dev_only");

    private static final RedpandaContainer REDPANDA = new RedpandaContainer("redpandadata/redpanda:v24.2.7");

    private static HikariDataSource dataSource;

    private Containers() {}

    public static synchronized PostgreSQLContainer<?> postgres() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        return POSTGRES;
    }

    public static synchronized RedpandaContainer redpanda() {
        if (!REDPANDA.isRunning()) {
            REDPANDA.start();
        }
        return REDPANDA;
    }

    /** Migrated, and with the application role created as the deployment's init script would. */
    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            postgres();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(POSTGRES.getJdbcUrl());
            config.setUsername(POSTGRES.getUsername());
            config.setPassword(POSTGRES.getPassword());
            config.setMaximumPoolSize(40);
            dataSource = new HikariDataSource(config);

            // docker/postgres/init/10-application-role.sh does this in a deployment; Testcontainers has
            // no init directory, and V3's grants need a subject.
            new JdbcTemplate(dataSource).execute("CREATE ROLE exchange_core_app LOGIN PASSWORD 'local_dev_only'");
            Flyway.configure().dataSource(dataSource).load().migrate();
        }
        return dataSource;
    }

    public static JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource());
    }

    /**
     * Clears order and ledger state between tests. {@code symbols} is left alone — it is seeded
     * reference data, not test state.
     */
    public static void reset() {
        jdbc().execute(
                "TRUNCATE outbox, processed_events, order_events, orders, idempotency_keys, postings, entries, accounts CASCADE");
    }
}
