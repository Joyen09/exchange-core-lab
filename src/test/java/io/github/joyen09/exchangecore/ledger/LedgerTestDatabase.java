package io.github.joyen09.exchangecore.ledger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One real PostgreSQL for every ledger test in the JVM, migrated by the real Flyway scripts.
 *
 * <p>Deliberately not a Spring context. The ledger's transaction boundaries are the subject of these
 * tests — a deferred constraint only fires at commit — so the tests drive commits and rollbacks
 * themselves rather than inheriting a rollback-by-default test transaction that would hide the very
 * behaviour under test (ADR-0004). It also lets the jqwik properties use the same database without
 * a JUnit-only extension.
 */
final class LedgerTestDatabase {

    private static final PostgreSQLContainer<?> CONTAINER = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("exchange_core_lab")
            .withUsername("exchange_core")
            .withPassword("local_dev_only");

    static final String APPLICATION_ROLE = "exchange_core_app";
    private static final String APPLICATION_PASSWORD = "local_dev_only";

    private static HikariDataSource ownerDataSource;
    private static HikariDataSource applicationDataSource;

    private LedgerTestDatabase() {}

    private static synchronized void start() {
        if (applicationDataSource != null) {
            return;
        }
        CONTAINER.start();

        // The owner: a superuser, used for migrations and test fixtures only — never for exercising
        // the ledger, because a superuser bypasses every ACL and would make the privilege layer
        // untestable.
        ownerDataSource = pool(CONTAINER.getUsername(), CONTAINER.getPassword(), 8);

        // In a deployment this role is created at cluster initialisation by
        // docker/postgres/init/10-application-role.sh. Testcontainers has no init directory, so the
        // same role is created here; V3 then grants it the privileges under test.
        new JdbcTemplate(ownerDataSource)
                .execute("CREATE ROLE %s LOGIN PASSWORD '%s'".formatted(APPLICATION_ROLE, APPLICATION_PASSWORD));

        Flyway.configure().dataSource(ownerDataSource).load().migrate();

        // Comfortably above the twenty threads the acceptance criteria run, so the test measures
        // lock contention rather than connection starvation.
        applicationDataSource = pool(APPLICATION_ROLE, APPLICATION_PASSWORD, 40);
    }

    private static HikariDataSource pool(String username, String password, int maximumPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(CONTAINER.getJdbcUrl());
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(maximumPoolSize);
        return new HikariDataSource(config);
    }

    /** What the application itself uses: the restricted, non-superuser role. */
    static DataSource dataSource() {
        start();
        return applicationDataSource;
    }

    /** Migrations and fixtures only. Everything the ledger does must work without this. */
    static DataSource ownerDataSource() {
        start();
        return ownerDataSource;
    }

    static JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource());
    }

    static JdbcTemplate ownerJdbc() {
        return new JdbcTemplate(ownerDataSource());
    }

    static PlatformTransactionManager transactionManager() {
        return new DataSourceTransactionManager(dataSource());
    }

    static TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager());
    }

    static TransactionTemplate transactionTemplate(int isolationLevel) {
        TransactionTemplate template = new TransactionTemplate(transactionManager());
        template.setIsolationLevel(isolationLevel);
        return template;
    }

    static LedgerService ledgerService() {
        return new LedgerService(new LedgerRepository(jdbc()), transactionTemplate());
    }

    static LedgerService ledgerService(int isolationLevel) {
        return new LedgerService(new LedgerRepository(jdbc()), transactionTemplate(isolationLevel));
    }

    static TransactionDefinition defaultDefinition() {
        return new TransactionTemplate(transactionManager());
    }

    /**
     * TRUNCATE rather than DELETE: postings refuse DELETE by trigger and by privilege. It runs as the
     * owner because the application role is not granted TRUNCATE either — which is the point.
     */
    static void reset() {
        ownerJdbc().execute("TRUNCATE postings, entries, accounts CASCADE");
    }
}
