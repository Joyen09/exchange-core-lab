package io.github.joyen09.exchangecore.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for the tests that need the real application context.
 *
 * <p>Only the URLs are overridden. The credentials stay exactly as {@code application.yml} declares
 * them — the application connecting as the restricted role and Flyway as the owner — so the split that
 * V3 and V4 depend on is exercised rather than configured away.
 *
 * <h2>Why the context is deliberately not cached</h2>
 *
 * This context runs the real scheduled outbox publisher, every 100 ms, against the shared
 * Testcontainers database. Spring's test framework normally keeps a context alive for the rest of the
 * JVM so later classes can reuse it — and a <em>live</em> context here means a publisher that keeps
 * polling long after its own test class has finished, draining the outbox that other test classes are
 * about to assert on. {@code OutboxPublisherIT} failed exactly that way in a full build while passing
 * in isolation: it expected forty unpublished rows and found thirty-five, because a previous class's
 * scheduler had helpfully sent five of them.
 *
 * <p>{@link DirtiesContext} closes the context when the class ends, which stops the scheduler with it.
 * The cost is one context start per Spring test class; the alternative is a suite whose results depend
 * on class execution order, which is not a trade worth making for a few seconds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class SpringIntegrationTest {

    @DynamicPropertySource
    static void wireContainers(DynamicPropertyRegistry registry) {
        // Touching the data source first creates the application role and migrates, as the init script
        // and Flyway would in a deployment.
        Containers.dataSource();
        registry.add("spring.datasource.url", () -> Containers.postgres().getJdbcUrl());
        registry.add("spring.kafka.bootstrap-servers", () -> Containers.redpanda().getBootstrapServers());
        // Fast enough that the end-to-end assertions do not have to wait long, slow enough to be a poll.
        registry.add("outbox.poll-interval", () -> "100ms");
    }
}
