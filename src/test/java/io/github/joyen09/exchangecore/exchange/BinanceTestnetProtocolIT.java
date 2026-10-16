package io.github.joyen09.exchangecore.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.joyen09.exchangecore.fakevenue.VenueFixtures;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The only test in this repository that talks to Binance. Skipped unless credentials are present.
 *
 * <h2>What it is for, and what it is not for</h2>
 *
 * ADR-0009 divides the work: the fake venue proves <b>behaviour</b>, because three of Phase 3's four
 * acceptance criteria cannot be produced on a real exchange; this proves <b>protocol</b>, because no
 * double can tell you what Binance actually sends.
 *
 * <p>So there is nothing here about orders ending in the right state. If assertions of that kind start
 * appearing, this has become a flaky duplicate of the offline suite and should be cut back.
 *
 * <h2>It is unverified, and the first run should be read with that in mind</h2>
 *
 * This file was written in an environment whose network policy denies {@code testnet.binance.vision}.
 * <b>It has never been executed.</b> A failure on its first run is at least as likely to be a bug in
 * this test as a difference in the protocol — read the output before believing the conclusion, which is
 * the same advice the rest of this project gives about every green tick.
 *
 * <h2>Running it</h2>
 *
 * <pre>
 *   export BINANCE_TESTNET_API_KEY=…
 *   export BINANCE_TESTNET_API_SECRET=…
 *   make testnet-check
 * </pre>
 *
 * Keys come from the Binance Spot Testnet portal and belong in a local {@code .env}, never in this
 * repository and never in a cloud session (SPEC §1.3). CI does not run this.
 */
@EnabledIfEnvironmentVariable(named = "BINANCE_TESTNET_API_KEY", matches = ".+")
class BinanceTestnetProtocolIT {

    private static final String BASE_URL = "https://testnet.binance.vision";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Where a capture lands. Never written into {@code src/}: promoting it is a deliberate commit. */
    private static final Path CAPTURE_DIR = Path.of("build", "captured-fixtures");

    private static final String API_KEY = System.getenv("BINANCE_TESTNET_API_KEY");
    private static final String API_SECRET = System.getenv("BINANCE_TESTNET_API_SECRET");

    @Test
    @DisplayName("the venue is reachable and its clock is close enough to ours for recvWindow")
    void serverTimeIsReachableAndClose() throws Exception {
        long before = System.currentTimeMillis();
        JsonNode time = JSON.readTree(get("/api/v3/time", null).body());
        long after = System.currentTimeMillis();

        long venue = time.get("serverTime").asLong();
        long skew = venue < before ? before - venue : (venue > after ? venue - after : 0);

        // recvWindow defaults to 5s and may not exceed 60s. A skew approaching that is the cause of
        // -1021 and is worth knowing about before it is diagnosed as a signing bug.
        assertThat(skew)
                .as("clock skew against the venue is %d ms; the adapter corrects for it at startup", skew)
                .isLessThan(5_000);
    }

    @Test
    @DisplayName("a request signed the way this project signs one is accepted")
    void theSignatureAlgorithmIsRight() throws Exception {
        // The single most valuable assertion here. If the signing is wrong, everything else fails with
        // -1022 and nothing else can be learned; if it is right, the fake venue's verifier agrees with
        // the real one about the one thing ADR-0009 says the real venue reports unambiguously.
        HttpResponse<String> response = signedGet("/api/v3/openOrders", "symbol=BTCUSDT");

        assertThat(response.statusCode())
                .as("body: %s", response.body())
                .isEqualTo(200);
        assertThat(JSON.readTree(response.body()).isArray()).isTrue();
    }

    @Test
    @DisplayName("myTrades still refuses a request without a symbol")
    void aDeliberateProtocolViolationIsStillRefused() throws Exception {
        // ADR-0009's second way of being fooled: a double more forgiving than the venue. The fake
        // refuses this with -1102; this confirms the venue does too, rather than that we assumed so.
        HttpResponse<String> response = signedGet("/api/v3/myTrades", "recvWindow=5000");

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode error = JSON.readTree(response.body());
        assertThat(error.get("code").asInt()).isEqualTo(-1102);
        assertThat(error.get("msg").asText()).contains("symbol");
    }

    @Test
    @DisplayName("the open-orders response carries the fields our fixture claims it does")
    void theFixtureStillMatchesReality() throws Exception {
        HttpResponse<String> response = signedGet("/api/v3/openOrders", "symbol=BTCUSDT");
        JsonNode orders = JSON.readTree(response.body());

        if (orders.isEmpty()) {
            // Nothing to compare against. Not a failure — place an order on the testnet and re-run if
            // the comparison is what you came for.
            System.out.println("""
                    [testnet-check] no open BTCUSDT orders, so the order fixture was not compared.
                                    Place one on the testnet and re-run to exercise this.""");
            return;
        }

        JsonNode live = orders.get(0);
        capture("order.json", live);

        TreeSet<String> ours = fieldsOf(VenueFixtures.order());
        TreeSet<String> theirs = fieldsOf(live);

        List<String> theySendWeDoNotHave = new ArrayList<>(theirs);
        theySendWeDoNotHave.removeAll(ours);
        List<String> weHaveTheyDoNotSend = new ArrayList<>(ours);
        weHaveTheyDoNotSend.removeAll(theirs);

        System.out.printf("""
                [testnet-check] order.json compared against a live response
                                they send, we do not model: %s
                                we model, they did not send: %s
                                a capture is in %s
                %n""", theySendWeDoNotHave, weHaveTheyDoNotSend, CAPTURE_DIR.resolve("order.json"));

        // Only the first direction fails. A field they send and we ignore is a gap in the model. A
        // field we carry that this particular response omits may simply be conditional — the fixture
        // documents which, and deciding from one sample would be the wrong call.
        assertThat(theySendWeDoNotHave)
                .as("the venue sends fields the fixture does not have; re-capture it and commit the "
                        + "diff on its own, per docs/vendor/README.md")
                .isEmpty();
    }

    // ---------------------------------------------------------------------------------------------

    private static TreeSet<String> fieldsOf(JsonNode node) {
        TreeSet<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void capture(String name, JsonNode node) throws Exception {
        Files.createDirectories(CAPTURE_DIR);
        Files.writeString(
                CAPTURE_DIR.resolve(name),
                node.toPrettyString() + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    private static HttpResponse<String> signedGet(String path, String query) throws Exception {
        String withTimestamp = query + "&timestamp=" + System.currentTimeMillis() + "&recvWindow=5000";
        return get(path, withTimestamp + "&signature=" + sign(withTimestamp));
    }

    private static HttpResponse<String> get(String path, String query) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path + (query == null ? "" : "?" + query)))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (API_KEY != null) {
            request.header("X-MBX-APIKEY", API_KEY);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String sign(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
