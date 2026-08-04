package io.github.joyen09.exchangecore.guard;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns the guard failure into a readable startup banner instead of a raw stack trace, so the
 * operator sees exactly which property is wrong and what the allowed values are.
 */
public class ExchangeEndpointNotAllowedFailureAnalyzer
        extends AbstractFailureAnalyzer<ExchangeEndpointNotAllowedException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ExchangeEndpointNotAllowedException cause) {
        String description =
                "The exchange endpoint configured in '%s' is not an allowed Binance Spot Testnet endpoint."
                        .formatted(cause.getPropertyName());
        String action =
                """
                Set '%s' to an allowed testnet endpoint (allowed hosts: %s).
                This check has no override: there is no property, profile, or environment variable
                that permits a non-testnet endpoint (SPEC §1.4).
                """
                        .formatted(cause.getPropertyName(), String.join(", ", ExchangeEndpointAllowlist.allowedHosts()));
        return new FailureAnalysis(description + System.lineSeparator() + cause.getMessage(), action, cause);
    }
}
