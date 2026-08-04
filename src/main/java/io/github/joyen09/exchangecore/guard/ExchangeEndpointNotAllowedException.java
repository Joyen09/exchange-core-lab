package io.github.joyen09.exchangecore.guard;

/**
 * Thrown when a configured exchange endpoint is not on the hard-coded testnet allowlist.
 *
 * <p>This exception is fatal by design. It is raised during environment preparation, before any
 * bean is created and before any network client exists, so the process cannot degrade into a
 * "warn and continue" mode.
 */
public class ExchangeEndpointNotAllowedException extends RuntimeException {

    private final String propertyName;
    private final String configuredValue;

    public ExchangeEndpointNotAllowedException(String propertyName, String configuredValue, String reason) {
        super(buildMessage(propertyName, configuredValue, reason));
        this.propertyName = propertyName;
        this.configuredValue = configuredValue;
    }

    public String getPropertyName() {
        return propertyName;
    }

    public String getConfiguredValue() {
        return configuredValue;
    }

    private static String buildMessage(String propertyName, String configuredValue, String reason) {
        return """
                REFUSING TO START: exchange endpoint is not on the testnet allowlist.

                  property        : %s
                  configured value: %s
                  reason          : %s
                  allowed hosts   : %s
                  allowed schemes : %s

                This service is a testnet-only laboratory (SPEC §1.4). Any endpoint outside the
                allowlist is rejected at startup. There is deliberately NO override flag, profile,
                or environment variable that can relax this check.
                """
                .formatted(
                        propertyName,
                        configuredValue == null ? "<not set>" : configuredValue,
                        reason,
                        String.join(", ", ExchangeEndpointAllowlist.allowedHosts()),
                        String.join(", ", ExchangeEndpointAllowlist.allowedSchemes()));
    }
}
