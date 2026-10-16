package io.github.joyen09.exchangecore.fakevenue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The fake venue's own behaviour, before anything is built on top of it. */
class FakeBinanceVenueTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private FakeBinanceVenue venue;

    @BeforeEach
    void start() {
        venue = new FakeBinanceVenue(VenueScript.builder().seed(20261016L).build());
    }

    @AfterEach
    void stop() {
        venue.close();
    }

    @Nested
    @DisplayName("signatures")
    class Signatures {

        @Test
        @DisplayName("a wrong signature is -1022, not a vague 400")
        void wrongSignatureIsRejected() throws Exception {
            HttpResponse<String> response = send("POST", "/api/v3/order",
                    "symbol=BTCUSDT&side=BUY&type=LIMIT&quantity=1&price=100&newClientOrderId=c-1",
                    "deadbeef");

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(code(response)).isEqualTo(-1022);
            assertThat(message(response)).isEqualTo("Signature for this request is not valid.");
        }

        @Test
        @DisplayName("a wrong API key is refused even with a correct signature")
        void wrongApiKeyIsRejected() throws Exception {
            String query = "symbol=BTCUSDT&side=BUY&type=LIMIT&quantity=1&price=100&newClientOrderId=c-2";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(venue.baseUrl() + "/api/v3/order?" + query + "&signature="
                            + VenueSignature.sign(query, FakeBinanceVenue.apiSecret())))
                    .header("X-MBX-APIKEY", "somebody-elses-key")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            assertThat(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(401);
        }

        @Test
        @DisplayName("the signature covers the query as sent, so reordering it invalidates it")
        void theSignatureIsOverTheExactString() throws Exception {
            // Signing a re-encoding of the parsed parameters would make the verifier agree with any
            // client that happens to order them the way the server does. That is agreement by
            // coincidence, which is the thing this whole approach is trying to avoid.
            String signed = "symbol=BTCUSDT&side=BUY&type=LIMIT&quantity=1&price=100&newClientOrderId=c-3";
            String sentInADifferentOrder =
                    "side=BUY&symbol=BTCUSDT&type=LIMIT&quantity=1&price=100&newClientOrderId=c-3";

            HttpResponse<String> response = send(
                    "POST", "/api/v3/order", sentInADifferentOrder,
                    VenueSignature.sign(signed, FakeBinanceVenue.apiSecret()));

            assertThat(code(response)).isEqualTo(-1022);
        }
    }

    @Nested
    @DisplayName("orders")
    class Orders {

        @Test
        @DisplayName("placing returns the recorded shape with this order's values")
        void placeEchoesTheOrder() throws Exception {
            JsonNode body = parse(place("c-10", "BTCUSDT", "0.5", "60000"));

            assertThat(body.get("clientOrderId").asText()).isEqualTo("c-10");
            assertThat(body.get("symbol").asText()).isEqualTo("BTCUSDT");
            assertThat(body.get("status").asText()).isEqualTo("NEW");
            assertThat(body.get("executedQty").asText()).isEqualTo("0");
            // The fixture's field set, unchanged — this is what "invents no bytes" means in practice.
            assertThat(fieldNames(body)).isEqualTo(fieldNames(VenueFixtures.order()));
        }

        @Test
        @DisplayName("a duplicate client order id is -2010, the overloaded code")
        void duplicateClientOrderIdIsRefused() throws Exception {
            place("c-11", "BTCUSDT", "0.5", "60000");

            HttpResponse<String> second = place("c-11", "BTCUSDT", "0.5", "60000");

            assertThat(code(second)).isEqualTo(-2010);
            assertThat(message(second))
                    .as("the adapter must not branch on this string, but the venue does send it")
                    .isEqualTo("Duplicate order sent.");
        }

        @Test
        @DisplayName("cancelling a filled order is refused, as the venue refuses it")
        void cancellingATerminalOrderIsRefused() throws Exception {
            place("c-12", "BTCUSDT", "1", "100");
            venue.applyFill("c-12", new BigDecimal("1"), new BigDecimal("100"));

            HttpResponse<String> response = signedSend("DELETE", "/api/v3/order",
                    "symbol=BTCUSDT&origClientOrderId=c-12");

            // A double more forgiving than the real venue would never teach the adapter to handle the
            // refusal — ADR-0009's second way of being fooled.
            assertThat(code(response)).isEqualTo(-2011);
        }

        @Test
        @DisplayName("fills accumulate into the cumulative figures the design depends on")
        void fillsAccumulate() throws Exception {
            place("c-13", "BTCUSDT", "1", "100");
            venue.applyFill("c-13", new BigDecimal("0.4"), new BigDecimal("100"));
            venue.applyFill("c-13", new BigDecimal("0.6"), new BigDecimal("110"));

            JsonNode body = parse(signedSend("GET", "/api/v3/order",
                    "symbol=BTCUSDT&origClientOrderId=c-13"));

            assertThat(body.get("executedQty").asText()).isEqualTo("1.0");
            assertThat(new BigDecimal(body.get("cummulativeQuoteQty").asText()))
                    .isEqualByComparingTo("106.0");
            assertThat(body.get("status").asText()).isEqualTo("FILLED");
        }

        @Test
        @DisplayName("open orders excludes terminal ones")
        void openOrdersExcludesTerminal() throws Exception {
            place("c-14", "BTCUSDT", "1", "100");
            place("c-15", "BTCUSDT", "1", "100");
            venue.applyFill("c-15", new BigDecimal("1"), new BigDecimal("100"));

            JsonNode open = parse(signedSend("GET", "/api/v3/openOrders", "symbol=BTCUSDT"));

            assertThat(open).hasSize(1);
            assertThat(open.get(0).get("clientOrderId").asText()).isEqualTo("c-14");
        }

        @Test
        @DisplayName("myTrades refuses a request without a symbol, as the venue does")
        void myTradesNeedsASymbol() throws Exception {
            HttpResponse<String> response = signedSend("GET", "/api/v3/myTrades", "startTime=0");

            assertThat(code(response)).isEqualTo(-1102);
            assertThat(message(response)).contains("Mandatory parameter 'symbol'");
        }

        @Test
        @DisplayName("a trade carries the venue's order id and nothing of ours")
        void tradesCarryNoClientOrderId() throws Exception {
            place("c-16", "BTCUSDT", "1", "100");
            venue.applyFill("c-16", new BigDecimal("1"), new BigDecimal("100"));

            JsonNode trades = parse(signedSend("GET", "/api/v3/myTrades", "symbol=BTCUSDT&startTime=0"));

            assertThat(trades).hasSize(1);
            assertThat(trades.get(0).has("clientOrderId")).isFalse();
            assertThat(trades.get(0).get("orderId").asLong()).isPositive();
        }
    }

    @Nested
    @DisplayName("scripted failures and limits")
    class Scripted {

        @Test
        @DisplayName("the first two attempts fail with 503 and the third succeeds")
        void scriptedFailuresAreConsumed() throws Exception {
            venue.close();
            venue = new FakeBinanceVenue(VenueScript.builder()
                    .seed(1L)
                    .httpFailure("POST /api/v3/order", 2, 503)
                    .build());

            assertThat(place("c-20", "BTCUSDT", "1", "100").statusCode()).isEqualTo(503);
            assertThat(place("c-20", "BTCUSDT", "1", "100").statusCode()).isEqualTo(503);

            HttpResponse<String> third = place("c-20", "BTCUSDT", "1", "100");

            assertThat(third.statusCode()).isEqualTo(200);
            assertThat(venue.orderOf("c-20")).as("exactly one order exists").isNotNull();
        }

        @Test
        @DisplayName("a 503 is returned before the signature is checked, because it is not a rejection")
        void transportFailuresPrecedeTheSignatureCheck() throws Exception {
            venue.close();
            venue = new FakeBinanceVenue(VenueScript.builder()
                    .seed(1L)
                    .httpFailure("POST /api/v3/order", 1, 503)
                    .build());

            // Even with a bad signature: the venue being unwell is not the same as the venue saying no,
            // and the adapter must see "no answer" rather than "invalid".
            HttpResponse<String> response = send("POST", "/api/v3/order",
                    "symbol=BTCUSDT&newClientOrderId=c-21", "not-a-signature");

            assertThat(response.statusCode()).isEqualTo(503);
        }

        @Test
        @DisplayName("exceeding the weight budget is 429 with Retry-After")
        void weightLimitProduces429() throws Exception {
            venue.close();
            venue = new FakeBinanceVenue(VenueScript.builder().seed(1L).weightLimit(10).build());

            place("c-30", "BTCUSDT", "1", "100");
            HttpResponse<String> response = null;
            for (int i = 0; i < 12 && (response == null || response.statusCode() != 429); i++) {
                response = signedSend("GET", "/api/v3/order", "symbol=BTCUSDT&origClientOrderId=c-30");
            }

            assertThat(response.statusCode()).isEqualTo(429);
            assertThat(code(response)).isEqualTo(-1003);
            assertThat(response.headers().firstValue("Retry-After")).contains("60");
        }

        @Test
        @DisplayName("every response reports the used weight, and openOrders without a symbol costs more")
        void usedWeightIsReported() throws Exception {
            place("c-31", "BTCUSDT", "1", "100");
            int afterQualified = Integer.parseInt(signedSend("GET", "/api/v3/openOrders", "symbol=BTCUSDT")
                    .headers()
                    .firstValue("X-MBX-USED-WEIGHT-1M")
                    .orElseThrow());
            int afterUnqualified = Integer.parseInt(signedSend("GET", "/api/v3/openOrders", "recvWindow=5000")
                    .headers()
                    .firstValue("X-MBX-USED-WEIGHT-1M")
                    .orElseThrow());

            // 6 against 80 — the asymmetry ExchangeClient's signature exists to make unwritable.
            assertThat(afterUnqualified - afterQualified).isEqualTo(80);
        }
    }

    @Nested
    @DisplayName("the venue invents no fields")
    class NoInvention {

        @Test
        @DisplayName("setting a field the fixture does not have is refused, with the reason")
        void aFieldOutsideTheFixtureIsRefused() {
            assertThatThrownBy(() -> VenueResponse.from(VenueFixtures.order()).with("totallyNewField", 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("would be inventing one")
                    .hasMessageContaining("re-capture it")
                    .hasMessageContaining("Fields the fixture does have");
        }

        @Test
        @DisplayName("a rendered order sets every state-dependent field the fixture carries")
        void nothingStateDependentIsLeftAtTheRecordedValue() throws Exception {
            place("c-40", "ETHBTC", "2", "0.05");

            JsonNode body = parse(signedSend("GET", "/api/v3/order",
                    "symbol=ETHBTC&origClientOrderId=c-40"));

            // Anything left at the fixture's recorded value is a field the venue is not modelling. These
            // four are genuinely constant for this venue; if the list grows, something is being served
            // from sample data and the test says so at the point it starts happening.
            assertThat(body.get("orderListId").asInt()).isEqualTo(-1);
            assertThat(body.get("stopPrice").asText()).isEqualTo("0.00000000");
            assertThat(body.get("icebergQty").asText()).isEqualTo("0.00000000");
            assertThat(body.get("selfTradePreventionMode").asText()).isEqualTo("NONE");
            assertThat(body.get("symbol").asText()).as("and the rest do track the order").isEqualTo("ETHBTC");
        }
    }

    // ---------------------------------------------------------------------------------------------

    private HttpResponse<String> place(String clientOrderId, String symbol, String qty, String price)
            throws Exception {
        return signedSend("POST", "/api/v3/order",
                "symbol=%s&side=BUY&type=LIMIT&timeInForce=GTC&quantity=%s&price=%s&newClientOrderId=%s"
                        .formatted(symbol, qty, price, clientOrderId));
    }

    private HttpResponse<String> signedSend(String method, String path, String query) throws Exception {
        return send(method, path, query, VenueSignature.sign(query, FakeBinanceVenue.apiSecret()));
    }

    private HttpResponse<String> send(String method, String path, String query, String signature)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(venue.baseUrl() + path + "?" + query + "&signature=" + signature))
                .header("X-MBX-APIKEY", FakeBinanceVenue.apiKey())
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode parse(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    private static int code(HttpResponse<String> response) throws Exception {
        return parse(response).get("code").asInt();
    }

    private static String message(HttpResponse<String> response) throws Exception {
        return parse(response).get("msg").asText();
    }

    private static java.util.List<String> fieldNames(JsonNode node) {
        java.util.List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
