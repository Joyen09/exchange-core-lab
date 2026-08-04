package io.github.joyen09.exchangecore.guard;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * Fail-fast startup guard for SPEC §1.4.
 *
 * <p>Runs immediately after configuration data has been loaded and before any bean is created, so a
 * misconfigured endpoint kills the process before a single HTTP client, data source, or scheduler
 * exists. Failing here — rather than in a bean validator — is what makes "cannot degrade to a
 * warning" true rather than aspirational.
 *
 * <p><b>Coverage is by namespace, not by enumeration</b> (see ADR-0003). An allowlist that named
 * two specific properties would mean every future endpoint property — the Phase 3 WebSocket stream,
 * a Phase 4 market data feed — starts life unguarded, and "add the property, forget the guard" is
 * precisely the accident this check exists to prevent. Instead every property under
 * {@code exchange.*} whose name or value looks like a URL is verified, so a new endpoint is
 * covered on the day it is introduced, by default, without anyone remembering to do anything.
 */
public class ExchangeEndpointGuard implements EnvironmentPostProcessor, Ordered {

    private static final String CANONICAL_PREFIX = "exchange.";

    /** Relaxed-binding form of the namespace, as it appears in a container's environment. */
    private static final String ENVIRONMENT_VARIABLE_PREFIX = "EXCHANGE_";

    /**
     * Endpoints the service cannot run without. Listed explicitly so that <em>removing</em> one
     * from configuration fails loudly instead of silently disabling its check.
     */
    private static final List<String> REQUIRED_PROPERTIES =
            List.of("exchange.rest-base-url", "exchange.ws-base-url");

    private static final Set<String> URL_NAME_SUFFIXES = Set.of("url", "uri", "endpoint");

    private static final Pattern SCHEME_PREFIX = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        for (String property : REQUIRED_PROPERTIES) {
            ExchangeEndpointAllowlist.verify(property, environment.getProperty(property));
        }
        for (String property : discoverEndpointProperties(environment)) {
            ExchangeEndpointAllowlist.verify(property, environment.getProperty(property));
        }
    }

    /**
     * Every configured property in the exchange namespace that carries an endpoint, whether or not
     * anyone declared it on a {@code @ConfigurationProperties} class.
     */
    private Set<String> discoverEndpointProperties(ConfigurableEnvironment environment) {
        Set<String> discovered = new LinkedHashSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                // Non-enumerable sources cannot be scanned. REQUIRED_PROPERTIES are still resolved
                // through them, and anything they contribute is overridden or shadowed by a source
                // that can be scanned.
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (!isInExchangeNamespace(name)) {
                    continue;
                }
                String value = environment.getProperty(name);
                if (value != null && looksLikeEndpoint(name, value)) {
                    discovered.add(name);
                }
            }
        }
        return discovered;
    }

    private static boolean isInExchangeNamespace(String name) {
        return name.startsWith(CANONICAL_PREFIX)
                || name.toUpperCase(Locale.ROOT).startsWith(ENVIRONMENT_VARIABLE_PREFIX);
    }

    /**
     * A property is treated as an endpoint if its <em>name</em> reads like one or its <em>value</em>
     * carries a URL scheme. Either signal is enough: matching on both would let
     * {@code exchange.fallback=https://…} through for want of the right suffix.
     */
    private static boolean looksLikeEndpoint(String name, String value) {
        return nameSuggestsEndpoint(name) || SCHEME_PREFIX.matcher(value).find();
    }

    private static boolean nameSuggestsEndpoint(String name) {
        String normalised = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return URL_NAME_SUFFIXES.stream().anyMatch(normalised::endsWith);
    }

    @Override
    public int getOrder() {
        // After ConfigDataEnvironmentPostProcessor so application.yml and profile-specific
        // overrides are already merged into the environment when we inspect it.
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
