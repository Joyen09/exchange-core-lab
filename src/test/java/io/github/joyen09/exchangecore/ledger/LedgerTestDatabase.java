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

    private static HikariDataSource dataSource;

    private LedgerTestDatabase() {}

    static synchronized DataSource dataSource() {
        if (dataSource == null) {
            CONTAINER.start();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(CONTAINER.getJdbcUrl());
            config.setUsername(CONTAINER.getUsername());
            config.setPassword(CONTAINER.getPassword());
            // Comfortably above the twenty threads the acceptance criteria run, so the test measures
            // lock contention rather than connection starvation.
            config.setMaximumPoolSize(40);
            dataSource = new HikariDataSource(config);
            Flyway.configure().dataSource(dataSource).load().migrate();
        }
        return dataSource;
    }

    static JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource());
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

    /** TRUNCATE rather than DELETE: the postings table refuses DELETE by trigger and by privilege. */
    static void reset() {
        jdbc().execute("TRUNCATE postings, entries, accounts CASCADE");
    }
}
