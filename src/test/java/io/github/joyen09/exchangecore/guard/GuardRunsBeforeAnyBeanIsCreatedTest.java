package io.github.joyen09.exchangecore.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.joyen09.exchangecore.ExchangeCoreLabApplication;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationContextInitializedEvent;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

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

    @Test
    @DisplayName("no application context is ever initialised and no bean is ever instantiated")
    void noContextIsInitialisedAndNoBeanIsInstantiated() {
        // The two tests above infer the ordering from what did *not* fail, which rests on the
        // database being unreachable. Point them at a running database and they would pass while
        // the guard ran late again. This one measures the proposition itself: it counts context
        // initialisation and bean instantiation, and passes no datasource override at all, so it
        // holds whether or not a database happens to be listening.
        StartupRecorder recorder = new StartupRecorder();

        assertThatThrownBy(() -> application()
                        .listeners(recorder)
                        .initializers(recorder)
                        .run("--exchange.rest-base-url=https://api.binance.com"))
                .satisfies(failure -> assertThat(chainOf(failure))
                        .hasAtLeastOneElementOfType(ExchangeEndpointNotAllowedException.class));

        assertThat(recorder.saw(ApplicationStartingEvent.class))
                .as("the recorder must have been wired into the run, or this test proves nothing")
                .isTrue();
        assertThat(recorder.saw(ApplicationEnvironmentPreparedEvent.class))
                .as("the guard aborts the environment-prepared multicast before later listeners are "
                        + "reached — which is itself a measure of how early it runs")
                .isFalse();
        assertThat(recorder.saw(ApplicationContextInitializedEvent.class))
                .as("an application context must never have been initialised")
                .isFalse();
        assertThat(recorder.saw(ApplicationPreparedEvent.class))
                .as("bean definitions must never have been loaded")
                .isFalse();
        assertThat(recorder.beansInstantiated())
                .as("not one bean may have been instantiated")
                .isZero();
    }

    @Test
    @DisplayName("the recorder does detect context initialisation, so a zero above means something")
    void theRecorderDetectsContextInitialisation() {
        // The control for the test above. A recorder that never records anything would make those
        // assertions unfalsifiable — the same failure mode this class exists to prevent.
        StartupRecorder recorder = new StartupRecorder();

        try (ConfigurableApplicationContext context = application()
                .listeners(recorder)
                .initializers(recorder)
                .run(UNREACHABLE_DATABASE, "--exchange.rest-base-url=https://testnet.binance.vision")) {
            // Reaching here means the environment allowed a full startup; either way the recorder
            // has already seen what it needs to.
        } catch (RuntimeException startupFailedAfterTheContextExisted) {
            // Expected when no database is listening. Deliberately swallowed: this test is about
            // what the recorder observed before that point, not about whether startup completed.
        }

        assertThat(recorder.saw(ApplicationContextInitializedEvent.class))
                .as("an allowed endpoint must let startup reach context initialisation")
                .isTrue();
        assertThat(recorder.beansInstantiated())
                .as("and beans must actually be instantiated, or the counter is not counting")
                .isPositive();
    }

    /**
     * Records how far startup got: which lifecycle events fired, and how many beans were
     * instantiated. Both signals are needed — the event says a context was created, the counter says
     * something was built inside it.
     */
    private static final class StartupRecorder
            implements ApplicationListener<ApplicationEvent>, ApplicationContextInitializer<ConfigurableApplicationContext> {

        private final Set<Class<?>> events = ConcurrentHashMap.newKeySet();
        private final AtomicInteger instantiations = new AtomicInteger();

        @Override
        public void onApplicationEvent(ApplicationEvent event) {
            events.add(event.getClass());
        }

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            context.getBeanFactory().addBeanPostProcessor(new InstantiationAwareBeanPostProcessor() {
                @Override
                public Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) {
                    instantiations.incrementAndGet();
                    return null;
                }
            });
        }

        boolean saw(Class<? extends ApplicationEvent> eventType) {
            return events.stream().anyMatch(eventType::isAssignableFrom);
        }

        int beansInstantiated() {
            return instantiations.get();
        }
    }

    private static List<Throwable> chainOf(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = failure; current != null && !chain.contains(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }
}
