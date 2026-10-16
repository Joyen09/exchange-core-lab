package io.github.joyen09.exchangecore.fakevenue;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The meta-test the Phase 3 specification asks for: one script, run twice, identical both times.
 *
 * <p>This is not a nicety. A chaos test that cannot be re-run identically is an anecdote, and the way
 * an anecdote gets handled is that somebody re-runs it until it is green. The twenty scripted
 * disconnects in acceptance criterion 1 are worth nothing unless failure number fourteen can be made
 * to happen again on demand.
 *
 * <p>Asserting on the sequence element by element rather than on a final state, because two runs can
 * agree about where they ended up while having taken different routes — and the route is the thing
 * under test.
 */
class FakeVenueDeterminismTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static VenueScript scriptWithSeed(long seed) {
        return VenueScript.builder()
                .seed(seed)
                .onOrder(
                        "chaos-1",
                        VenueScript.fill("0.1", "60000"),
                        VenueScript.fill("0.2", "60010"),
                        VenueScript.disconnectStream(),
                        VenueScript.fill("0.1", "60020"),
                        VenueScript.fill("0.1", "60030"))
                .httpFailure("POST /api/v3/order", 2, 503)
                .build();
    }

    @Test
    @DisplayName("the same seed produces the same sequence, element for element")
    void oneScriptRunTwiceIsIdentical() throws Exception {
        List<String> first = run(scriptWithSeed(20261016L));
        List<String> second = run(scriptWithSeed(20261016L));

        assertThat(first).isNotEmpty();
        assertThat(second).containsExactlyElementsOf(first);
    }

    @Test
    @DisplayName("a different seed is allowed to differ, or the seed is decoration")
    void theSeedIsActuallyConsulted() {
        // Without this, "same seed, same sequence" is satisfied by a script that ignores the seed
        // entirely — which is the usual way a determinism claim turns out to be about nothing.
        List<Integer> fromOneSeed = draw(scriptWithSeed(1L));
        List<Integer> fromAnother = draw(scriptWithSeed(2L));

        assertThat(fromOneSeed).isNotEqualTo(fromAnother);
        assertThat(draw(scriptWithSeed(1L))).isEqualTo(fromOneSeed);
    }

    @Test
    @DisplayName("scripted HTTP failures are consumed in order, not re-evaluated per call")
    void failuresAreASequenceNotAPredicate() {
        VenueScript script = scriptWithSeed(7L);

        assertThat(script.nextFailureFor("POST /api/v3/order")).isEqualTo(503);
        assertThat(script.nextFailureFor("POST /api/v3/order")).isEqualTo(503);
        assertThat(script.nextFailureFor("POST /api/v3/order"))
                .as("times(2) means the third attempt succeeds; a predicate would fail forever")
                .isNull();
    }

    /** The observable sequence of one run: every response code, then the venue's resulting state. */
    private static List<String> run(VenueScript script) throws Exception {
        List<String> observed = new ArrayList<>();
        try (FakeBinanceVenue venue = new FakeBinanceVenue(script)) {
            for (int attempt = 0; attempt < 3; attempt++) {
                observed.add("place -> " + place(venue, "chaos-1").statusCode());
            }
            venue.runScriptFor("chaos-1");

            FakeBinanceVenue.VenueOrder order = venue.orderOf("chaos-1");
            observed.add("status=" + order.status());
            observed.add("executed=" + order.executedQuantity().toPlainString());
            observed.add("quote=" + order.cumulativeQuote().toPlainString());
            for (FakeBinanceVenue.VenueTrade trade : venue.tradesOf("BTCUSDT")) {
                observed.add("trade %d %s @ %s"
                        .formatted(trade.tradeId(), trade.quantity().toPlainString(), trade.price().toPlainString()));
            }
        }
        return observed;
    }

    private static List<Integer> draw(VenueScript script) {
        Random random = script.random();
        List<Integer> drawn = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            drawn.add(random.nextInt(1_000));
        }
        return drawn;
    }

    private static HttpResponse<String> place(FakeBinanceVenue venue, String clientOrderId) throws Exception {
        String query = "symbol=BTCUSDT&side=BUY&type=LIMIT&timeInForce=GTC&quantity=1&price=60000"
                + "&newClientOrderId=" + clientOrderId;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(venue.baseUrl() + "/api/v3/order?" + query + "&signature="
                        + VenueSignature.sign(query, FakeBinanceVenue.apiSecret())))
                .header("X-MBX-APIKEY", FakeBinanceVenue.apiKey())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unused")
    private static BigDecimal unused() {
        return BigDecimal.ZERO;
    }
}
