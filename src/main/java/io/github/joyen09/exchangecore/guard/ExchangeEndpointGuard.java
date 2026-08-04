package io.github.joyen09.exchangecore.guard;

import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Fail-fast startup guard for SPEC §1.4.
 *
 * <p>Runs immediately after configuration data has been loaded and before any bean is created, so a
 * misconfigured endpoint kills the process before a single HTTP client, data source, or scheduler
 * exists. Failing here — rather than in a bean validator — is what makes "cannot degrade to a
 * warning" true rather than aspirational.
 */
public class ExchangeEndpointGuard implements EnvironmentPostProcessor, Ordered {

    static final String REST_BASE_URL_PROPERTY = "exchange.rest-base-url";
    static final String WS_BASE_URL_PROPERTY = "exchange.ws-base-url";

    private static final List<String> GUARDED_PROPERTIES = List.of(REST_BASE_URL_PROPERTY, WS_BASE_URL_PROPERTY);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        for (String property : GUARDED_PROPERTIES) {
            ExchangeEndpointAllowlist.verify(property, environment.getProperty(property));
        }
    }

    @Override
    public int getOrder() {
        // After ConfigDataEnvironmentPostProcessor so application.yml and profile-specific
        // overrides are already merged into the environment when we inspect it.
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
