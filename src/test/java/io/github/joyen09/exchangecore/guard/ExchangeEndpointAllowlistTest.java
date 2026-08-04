package io.github.joyen09.exchangecore.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The allowlist is the whole point of SPEC §1.4, so it is tested adversarially: the cases below are
 * the ways a naive implementation ({@code contains("testnet")}, {@code endsWith(...)}, or trusting
 * the string before parsing it) would let a mainnet endpoint through.
 */
class ExchangeEndpointAllowlistTest {

    private static final String PROPERTY = "exchange.rest-base-url";

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://testnet.binance.vision",
                "https://testnet.binance.vision/",
                "https://testnet.binance.vision/api/v3/order",
                "https://TESTNET.BINANCE.VISION",
                "wss://stream.testnet.binance.vision:9443",
                "wss://stream.testnet.binance.vision:9443/ws"
            })
    @DisplayName("accepts Binance Spot Testnet endpoints over TLS")
    void acceptsTestnetEndpoints(String url) {
        assertThatCode(() -> ExchangeEndpointAllowlist.verify(PROPERTY, url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // Plain mainnet.
                "https://api.binance.com",
                "https://api1.binance.com/api/v3/order",
                "https://fapi.binance.com",
                "wss://stream.binance.com:9443/ws",
                // Mainnet dressed up to defeat substring matching.
                "https://api.binance.com/api/v3/order?note=testnet.binance.vision",
                "https://api.binance.com#testnet.binance.vision",
                // Lookalike hosts that defeat endsWith / startsWith matching.
                "https://testnet.binance.vision.attacker.example",
                "https://nottestnet.binance.vision",
                "https://testnet.binance.vision.",
                "https://evil-testnet.binance.vision",
                // User-info disguise: the real host is after the '@'.
                "https://testnet.binance.vision@api.binance.com/api/v3/order",
                "wss://stream.testnet.binance.vision@stream.binance.com:9443"
            })
    @DisplayName("rejects mainnet, lookalike, and disguised hosts")
    void rejectsNonTestnetHosts(String url) {
        assertThatThrownBy(() -> ExchangeEndpointAllowlist.verify(PROPERTY, url))
                .isInstanceOf(ExchangeEndpointNotAllowedException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://testnet.binance.vision",
                "ws://stream.testnet.binance.vision:9443",
                "ftp://testnet.binance.vision"
            })
    @DisplayName("rejects non-TLS schemes even for allowed hosts")
    void rejectsPlaintextSchemes(String url) {
        assertThatThrownBy(() -> ExchangeEndpointAllowlist.verify(PROPERTY, url))
                .isInstanceOf(ExchangeEndpointNotAllowedException.class)
                .hasMessageContaining("is not an allowed TLS scheme");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "testnet.binance.vision", "/api/v3/order", "not a url", " https://testnet.binance.vision"})
    @DisplayName("rejects blank, relative, and malformed values")
    void rejectsMalformedValues(String url) {
        assertThatThrownBy(() -> ExchangeEndpointAllowlist.verify(PROPERTY, url))
                .isInstanceOf(ExchangeEndpointNotAllowedException.class);
    }

    @Test
    @DisplayName("failure message names the property, the rejected value, and the allowed hosts")
    void failureMessageIsActionable() {
        assertThatThrownBy(() -> ExchangeEndpointAllowlist.verify(PROPERTY, "https://api.binance.com"))
                .isInstanceOf(ExchangeEndpointNotAllowedException.class)
                .hasMessageContaining("REFUSING TO START")
                .hasMessageContaining(PROPERTY)
                .hasMessageContaining("https://api.binance.com")
                .hasMessageContaining("testnet.binance.vision")
                .hasMessageContaining("NO override");
    }

    @Test
    @DisplayName("allowlist contains only Binance Spot Testnet hosts")
    void allowlistIsTestnetOnly() {
        assertThat(ExchangeEndpointAllowlist.allowedHosts())
                .containsExactlyInAnyOrder("testnet.binance.vision", "stream.testnet.binance.vision");
        assertThat(ExchangeEndpointAllowlist.allowedSchemes()).containsExactlyInAnyOrder("https", "wss");
    }
}
