package io.github.joyen09.exchangecore.guard;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * The single source of truth for which exchange endpoints this service may talk to.
 *
 * <p>Design notes (SPEC §1.4):
 *
 * <ul>
 *   <li>The allowlist is a compile-time constant. It is never read from configuration, so no
 *       deployment, profile, or environment variable can widen it.
 *   <li>Matching is done on the parsed {@link URI} host with <em>exact equality</em>, never with
 *       {@code contains}/{@code endsWith}. Substring matching would accept lookalike hosts such as
 *       {@code testnet.binance.vision.example.com} or a mainnet URL carrying "testnet" in its query
 *       string.
 *   <li>User-info is rejected outright, because {@code https://testnet.binance.vision@example.com}
 *       reads as a testnet URL to a human but resolves to {@code example.com}.
 *   <li>Only TLS schemes are accepted; plaintext {@code http}/{@code ws} is refused so that a
 *       transparent proxy cannot be interposed.
 * </ul>
 *
 * <p>There is no {@code allow}/{@code skip}/{@code force} entry point in this class on purpose.
 */
public final class ExchangeEndpointAllowlist {

    private static final Set<String> ALLOWED_HOSTS =
            Set.of("testnet.binance.vision", "stream.testnet.binance.vision");

    private static final Set<String> ALLOWED_SCHEMES = Set.of("https", "wss");

    private ExchangeEndpointAllowlist() {
        // utility
    }

    public static Set<String> allowedHosts() {
        return ALLOWED_HOSTS;
    }

    public static Set<String> allowedSchemes() {
        return ALLOWED_SCHEMES;
    }

    /**
     * Verifies a configured endpoint, throwing if it is not an allowed testnet endpoint.
     *
     * @param propertyName the configuration key, used only to make the failure message actionable
     * @param rawUrl the configured value
     * @throws ExchangeEndpointNotAllowedException if the value is missing, unparseable, or points
     *     anywhere other than an allowed testnet host
     */
    public static void verify(String propertyName, String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new ExchangeEndpointNotAllowedException(propertyName, rawUrl, "value is missing or blank");
        }
        if (!rawUrl.equals(rawUrl.strip())) {
            throw new ExchangeEndpointNotAllowedException(
                    propertyName, rawUrl, "value has leading or trailing whitespace");
        }
        if (rawUrl.chars().anyMatch(Character::isWhitespace)) {
            throw new ExchangeEndpointNotAllowedException(propertyName, rawUrl, "value contains whitespace");
        }

        final URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw new ExchangeEndpointNotAllowedException(
                    propertyName, rawUrl, "value is not a valid URI (" + e.getReason() + ")");
        }

        if (!uri.isAbsolute()) {
            throw new ExchangeEndpointNotAllowedException(
                    propertyName, rawUrl, "value is not an absolute URL (no scheme)");
        }

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new ExchangeEndpointNotAllowedException(
                    propertyName, rawUrl, "scheme '" + scheme + "' is not an allowed TLS scheme");
        }

        if (uri.getUserInfo() != null || (uri.getRawAuthority() != null && uri.getRawAuthority().contains("@"))) {
            throw new ExchangeEndpointNotAllowedException(
                    propertyName, rawUrl, "URL carries user-info, which can disguise the real host");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ExchangeEndpointNotAllowedException(
                    propertyName, rawUrl, "URL has no parseable host");
        }

        String normalisedHost = host.toLowerCase(Locale.ROOT);
        if (!ALLOWED_HOSTS.contains(normalisedHost)) {
            throw new ExchangeEndpointNotAllowedException(
                    propertyName, rawUrl, "host '" + normalisedHost + "' is not on the testnet allowlist");
        }
    }
}
