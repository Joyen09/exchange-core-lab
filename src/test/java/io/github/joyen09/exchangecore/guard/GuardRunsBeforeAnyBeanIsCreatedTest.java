package io.github.joyen09.exchangecore.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.joyen09.exchangecore.ExchangeCoreLabApplication;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Proves <em>where</em> the guard fails, not merely <em>that</em> it fails.
 *
 * <p>This test exists because its absence hid a real defect for an entire phase. The guard was
 * registered in {@code META-INF/spring/…EnvironmentPostProcessor.imports}, which Spring Boot does not
 * read for this type — that mechanism is for auto-configuration only — so the environment
 * post-processor never ran. What actually rejected mainnet endpoints was the constructor check on
 * {@code ExchangeProperties}: a bean, created during context refresh, which is exactly the late
 * failure ADR-0001 records as rejected. Every existing test still passed, because they only asked
 * whether startup failed.
 *
 * <p>It went unnoticed until the ledger arrived and changed bean creation order, so a database
 * failure started winning the race. That is the kind of accident that turns a guarantee into a
 * coincidence.
 *
 * <p>So: assert the ordering directly. With an unreachable database <em>and</em> a mainnet endpoint,
 * the failure must be the guard, and the chain must contain no bean creation failure and no
 * connection attempt — because neither should ever have been reached.
 */
class GuardRunsBeforeAnyBeanIsCreatedTest {

    /** A port nothing listens on, so touching the database is guaranteed to fail loudly. */
    private static final String UNREACHABLE_DATABASE = "--spring.datasource.url=jdbc:postgresql://localhost:1/nowhere";

    private static SpringApplicationBuilder application() {
        return new SpringApplicationBuilder(ExchangeCoreLabApplication.class).web(WebApplicationType.NONE);
    }

    @Test
    @DisplayName("a mainnet endpoint fails before any bean exists, not during context refresh")
    void guardFailsBeforeAnyBeanIsCreated() {
        assertThatThrownBy(() -> application()
                        .run(UNREACHABLE_DATABASE, "--exchange.rest-base-url=https://api.binance.com"))
                .satisfies(failure -> {
                    List<Throwable> chain = chainOf(failure);

                    assertThat(chain)
                            .as("the endpoint guard must be what stopped startup")
                            .hasAtLeastOneElementOfType(ExchangeEndpointNotAllowedException.class);
                    assertThat(chain)
                            .as("no bean may have been created — a bean failure means the guard ran too late")
                            .noneMatch(BeansException.class::isInstance);
                    assertThat(chain)
                            .as("the database must never have been contacted")
                            .noneMatch(java.net.ConnectException.class::isInstance);
                });
    }

    @Test
    @DisplayName("the unreachable database really is unreachable, so the test above is not vacuous")
    void theDatabaseIsGenuinelyUnreachable() {
        // Without this, a typo in the datasource URL would make the assertions above pass for the
        // wrong reason — the same class of mistake this whole test class exists to catch.
        assertThatThrownBy(() -> application()
                        .run(UNREACHABLE_DATABASE, "--exchange.rest-base-url=https://testnet.binance.vision"))
                .satisfies(failure -> {
                    List<Throwable> chain = chainOf(failure);

                    assertThat(chain)
                            .as("a valid testnet endpoint must let startup proceed as far as the database")
                            .hasAtLeastOneElementOfType(BeansException.class)
                            .noneMatch(ExchangeEndpointNotAllowedException.class::isInstance);
                });
    }

    private static List<Throwable> chainOf(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = failure; current != null && !chain.contains(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }
}
