package io.github.joyen09.exchangecore.config;

import io.github.joyen09.exchangecore.outbox.OutboxProperties;
import java.time.Clock;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Beans the application assembles by hand, each for a reason worth stating. */
@Configuration
public class ApplicationConfiguration {

    /**
     * A single clock, injected everywhere time is read.
     *
     * <p>Not {@code Instant.now()} scattered through the code: the outbox's backoff and the idempotency
     * retention are both time-dependent behaviours that tests need to drive, and a clock is the seam
     * that makes that possible without sleeping.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Replaces Boot's transaction manager solely to allow nested transactions.
     *
     * <p>Savepoints are what let the funds-lock attempt fail without destroying the enclosing
     * transaction, which is what makes a persisted {@code REJECTED} order possible — see
     * {@code OrderFundsLock}. Without this flag Spring refuses {@code PROPAGATION_NESTED} outright.
     */
    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        manager.setNestedTransactionAllowed(true);
        return manager;
    }

    /**
     * The ordinary template. Declared explicitly because declaring the savepoint one below would
     * otherwise suppress Boot's auto-configured bean and leave the nested template as the only
     * candidate — which would silently turn every transaction in the application into a savepoint.
     */
    @Bean
    @Primary
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /** For sub-operations that may fail without taking the enclosing unit of work with them. */
    @Bean
    public TransactionTemplate savepointTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        return template;
    }

    /**
     * Declared rather than auto-created, so the partition count is a decision on the record: one
     * partition, because ordering per order is guaranteed by the partition key and a single partition
     * makes that trivially true. Phase 3 can raise it; the key stays the order id either way.
     */
    @Bean
    public NewTopic orderEventsTopic(OutboxProperties properties) {
        return TopicBuilder.name(properties.getTopic()).partitions(1).replicas(1).build();
    }
}
