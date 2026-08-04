package io.github.joyen09.exchangecore.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.joyen09.exchangecore.ExchangeCoreLabApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Phase 0 acceptance criterion: pointing the service at a non-allowlisted base URL must abort
 * startup with a clear message.
 *
 * <p>Values are passed as command-line arguments because those sit at the top of Spring's property
 * precedence — this is the same path an operator would take with {@code -Dexchange.rest-base-url}
 * or a container environment variable, so the test exercises the real override route rather than a
 * weaker one that {@code application.yml} would win against.
 *
 * <p>No database is required: the guard runs during environment preparation, before any bean —
 * including the data source — is created. That ordering is itself part of what is being asserted.
 */
class MainnetStartupFailsTest {

    private static SpringApplicationBuilder application() {
        return new SpringApplicationBuilder(ExchangeCoreLabApplication.class).web(WebApplicationType.NONE);
    }

    @Test
    @DisplayName("mainnet REST base URL aborts startup")
    void mainnetRestBaseUrlAbortsStartup() {
        assertThatThrownBy(() -> application().run("--exchange.rest-base-url=https://api.binance.com"))
                .satisfies(failure -> {
                    ExchangeEndpointNotAllowedException cause = guardFailure(failure);
                    assertThat(cause.getPropertyName()).isEqualTo("exchange.rest-base-url");
                    assertThat(cause.getConfiguredValue()).isEqualTo("https://api.binance.com");
                    assertThat(cause.getMessage())
                            .contains("REFUSING TO START")
                            .contains("is not on the testnet allowlist")
                            .contains("testnet.binance.vision");
                });
    }

    @Test
    @DisplayName("mainnet WebSocket base URL aborts startup")
    void mainnetWebSocketBaseUrlAbortsStartup() {
        assertThatThrownBy(() -> application().run("--exchange.ws-base-url=wss://stream.binance.com:9443/ws"))
                .satisfies(failure -> assertThat(guardFailure(failure).getPropertyName())
                        .isEqualTo("exchange.ws-base-url"));
    }

    @Test
    @DisplayName("a host that merely looks like the testnet aborts startup")
    void lookalikeHostAbortsStartup() {
        assertThatThrownBy(() ->
                        application().run("--exchange.rest-base-url=https://testnet.binance.vision.attacker.example"))
                .satisfies(failure -> assertThat(guardFailure(failure)).isNotNull());
    }

    @Test
    @DisplayName("no property, profile, or flag can wave a mainnet endpoint through")
    void inventedOverrideFlagsDoNotHelp() {
        // Every plausible shape of a back door, all at once. The guard reads none of them, and the
        // allowlist is a compile-time constant, so the only possible outcome is still a hard stop.
        assertThatThrownBy(() -> application()
                        .run(
                                "--exchange.rest-base-url=https://api.binance.com",
                                "--exchange.endpoint-check.enabled=false",
                                "--exchange.unsafe-endpoint-override=true",
                                "--spring.profiles.active=production"))
                .satisfies(failure -> assertThat(guardFailure(failure).getConfiguredValue())
                        .isEqualTo("https://api.binance.com"));
    }

    private static ExchangeEndpointNotAllowedException guardFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ExchangeEndpointNotAllowedException guardFailure) {
                return guardFailure;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        throw new AssertionError(
                "startup failed, but not because of the endpoint guard: " + failure, failure);
    }
}
