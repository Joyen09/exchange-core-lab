package io.github.joyen09.exchangecore.config;

import io.github.joyen09.exchangecore.guard.ExchangeEndpointAllowlist;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Exchange connection settings.
 *
 * <p>The constructor re-verifies both endpoints against {@link ExchangeEndpointAllowlist}. This is
 * defence in depth: {@code ExchangeEndpointGuard} already failed the process during environment
 * preparation, but binding these properties programmatically (in a test, or in future code that
 * builds a context by hand) must not be able to slip past the allowlist either.
 */
@Validated
@ConfigurationProperties(prefix = "exchange")
public class ExchangeProperties {

    private final String restBaseUrl;
    private final String wsBaseUrl;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public ExchangeProperties(
            @NotBlank String restBaseUrl,
            @NotBlank String wsBaseUrl,
            Duration connectTimeout,
            Duration readTimeout) {
        ExchangeEndpointAllowlist.verify("exchange.rest-base-url", restBaseUrl);
        ExchangeEndpointAllowlist.verify("exchange.ws-base-url", wsBaseUrl);
        this.restBaseUrl = restBaseUrl;
        this.wsBaseUrl = wsBaseUrl;
        // SPEC §5.3: every outbound call must have a timeout. Defaults are conservative and
        // explicit rather than "whatever the client library does".
        this.connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        this.readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
    }

    public String getRestBaseUrl() {
        return restBaseUrl;
    }

    public String getWsBaseUrl() {
        return wsBaseUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }
}
