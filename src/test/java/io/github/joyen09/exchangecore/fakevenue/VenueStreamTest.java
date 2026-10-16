package io.github.joyen09.exchangecore.fakevenue;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The user data stream, and the three things it exists to be able to do badly.
 *
 * <p>Everything here is about <em>timing</em>. The bytes are the fixture's; what is under test is that
 * the double can disconnect at a chosen moment, keep recording while nobody is listening, and deliver
 * an event before the HTTP response that caused it. Those are the behaviours a recording cannot supply
 * and the reason ADR-0009 rejected record-and-replay as a source of scenarios.
 */
@Timeout(30)
class VenueStreamTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private FakeBinanceVenue venue;

    @BeforeEach
    void start() {
        venue = new FakeBinanceVenue(VenueScript.builder().seed(20261017L).build());
    }

    @AfterEach
    void stop() {
        venue.close();
    }

    @Test
    @DisplayName("a listenKey is created, renewed and deleted, and the stream follows it")
    void listenKeyLifecycle() throws Exception {
        String listenKey = createListenKey();
        assertThat(listenKey).isNotBlank();

        Recorder recorder = connect(listenKey);
        assertThat(venue.stream().awaitConnection(Duration.ofSeconds(5))).isTrue();

        // Renewal keeps the same key working. A client that stops renewing finds out sixty minutes
        // later, which is why the adapter renews at thirty.
        assertThat(userDataStream("PUT").statusCode()).isEqualTo(200);
        assertThat(venue.stream().isConnected()).isTrue();

        assertThat(userDataStream("DELETE").statusCode()).isEqualTo(200);
        recorder.awaitClosed();
        assertThat(venue.stream().isConnected()).isFalse();
    }

    @Test
    @DisplayName("a stale listenKey is refused rather than quietly served")
    void aStaleListenKeyIsRefused() throws Exception {
        createListenKey();
        String stale = "listen-key-does-not-exist";

        AtomicBoolean failed = new AtomicBoolean();
        try {
            HTTP.newWebSocketBuilder()
                    .buildAsync(URI.create(venue.stream().url(stale)), new Recorder())
                    .join();
        } catch (Exception e) {
            failed.set(true);
        }

        // A double that served any path would let a renewal bug through unnoticed: the client would
        // reconnect with an expired key, be served, and nobody would learn that renewal had stopped.
        assertThat(failed).isTrue();
    }

    @Test
    @DisplayName("execution reports arrive, carrying cumulative figures and the -1 sentinel")
    void reportsArrive() throws Exception {
        String listenKey = createListenKey();
        Recorder recorder = connect(listenKey);
        venue.stream().awaitConnection(Duration.ofSeconds(5));

        place("s-1", "BTCUSDT", "1", "100");
        venue.applyFill("s-1", new BigDecimal("0.4"), new BigDecimal("100"));

        recorder.awaitAtLeast(2);
        List<JsonNode> events = recorder.events();

        JsonNode created = events.get(0);
        assertThat(created.get("x").asText()).isEqualTo("NEW");
        assertThat(created.get("t").asLong())
                .as("a NEW event is not a fill, and the venue says so with -1")
                .isEqualTo(-1L);

        JsonNode fill = events.get(1);
        assertThat(fill.get("x").asText()).isEqualTo("TRADE");
        assertThat(fill.get("t").asLong()).as("a real trade id this time").isNotNegative();
        assertThat(fill.get("z").asText()).as("cumulative, not the increment").isEqualTo("0.4");
        assertThat(fill.get("l").asText()).as("and the increment alongside it").isEqualTo("0.4");
        assertThat(new BigDecimal(fill.get("Z").asText())).isEqualByComparingTo("40");
    }

    @Test
    @DisplayName("fills that happen while the client is away are recorded, and only REST can find them")
    void fillsDuringADisconnectAreStillRecorded() throws Exception {
        String listenKey = createListenKey();
        Recorder recorder = connect(listenKey);
        venue.stream().awaitConnection(Duration.ofSeconds(5));

        place("s-2", "BTCUSDT", "1", "100");
        venue.applyFill("s-2", new BigDecimal("0.3"), new BigDecimal("100"));
        recorder.awaitAtLeast(2);

        venue.stream().drop();
        recorder.awaitClosed();

        // The fill nobody hears about. This is acceptance criterion 1's whole premise: the event
        // happened, the venue knows, and the client can only learn of it by reconnecting and asking.
        venue.applyFill("s-2", new BigDecimal("0.7"), new BigDecimal("110"));

        assertThat(recorder.events())
                .as("the client saw nothing of the second fill")
                .hasSize(2);
        assertThat(venue.orderOf("s-2").status())
                .as("but the venue completed the order anyway")
                .isEqualTo("FILLED");
        assertThat(venue.tradesOf("BTCUSDT")).hasSize(2);
        assertThat(venue.stream().sent())
                .as("the stream emitted it; there was simply nobody on the other end")
                .hasSize(3);
    }

    @Test
    @DisplayName("the drop is abrupt, not a polite close")
    void theDisconnectIsImpolite() throws Exception {
        String listenKey = createListenKey();
        Recorder recorder = connect(listenKey);
        venue.stream().awaitConnection(Duration.ofSeconds(5));

        venue.stream().drop();
        recorder.awaitClosed();

        // A client that handles only the polite shutdown has never been tested against what actually
        // happens to a TCP connection on a bad day.
        assertThat(recorder.closedPolitely())
                .as("a close frame would be the venue saying goodbye; a network failure does not")
                .isFalse();
    }

    @Test
    @DisplayName("the report can arrive before the REST response that caused it")
    void theReportCanPrecedeTheResponse() throws Exception {
        // ADR-0009's third way of being fooled. The natural implementation answers the HTTP call and
        // then pushes the event; a client built against only that ordering has a race nobody looked
        // for. The double has to be able to do the inconvenient thing, or "deterministic" quietly
        // becomes "always convenient".
        String listenKey = createListenKey();
        Recorder recorder = connect(listenKey);
        venue.stream().awaitConnection(Duration.ofSeconds(5));

        venue.deliverReportBeforeResponse(true);
        HttpResponse<String> response = place("s-3", "BTCUSDT", "1", "100");

        assertThat(response.statusCode()).isEqualTo(200);
        recorder.awaitAtLeast(1);
        assertThat(recorder.events().get(0).get("c").asText()).isEqualTo("s-3");

        // Asserted on the venue's own record rather than on two clocks. The frame and the HTTP
        // response travel on different TCP connections, so comparing when each arrived is a race the
        // test would lose at random — and the property under test is whether the double can emit the
        // report first, which is a fact about the double.
        assertThat(venue.actions()).containsExactly("push:s-3", "respond:s-3");
    }

    @Test
    @DisplayName("and the ordinary ordering is the other way round, or the previous test proves nothing")
    void theDefaultOrderingIsResponseThenReport() throws Exception {
        String listenKey = createListenKey();
        Recorder recorder = connect(listenKey);
        venue.stream().awaitConnection(Duration.ofSeconds(5));

        // Without this, "the report can arrive first" is satisfied by a venue that always sends it
        // first — which would be its own kind of unrealistic, and would make the flag decorative.
        place("s-4", "BTCUSDT", "1", "100");
        recorder.awaitAtLeast(1);

        assertThat(venue.actions()).containsExactly("respond:s-4", "push:s-4");
    }

    // ---------------------------------------------------------------------------------------------

    private String createListenKey() throws Exception {
        return JSON.readTree(userDataStream("POST").body()).get("listenKey").asText();
    }

    private HttpResponse<String> userDataStream(String method) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(venue.baseUrl() + "/api/v3/userDataStream"))
                .header("X-MBX-APIKEY", FakeBinanceVenue.apiKey())
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Recorder connect(String listenKey) {
        Recorder recorder = new Recorder();
        HTTP.newWebSocketBuilder()
                .buildAsync(URI.create(venue.stream().url(listenKey)), recorder)
                .join();
        return recorder;
    }

    private HttpResponse<String> place(String clientOrderId, String symbol, String qty, String price)
            throws Exception {
        String query = "symbol=%s&side=BUY&type=LIMIT&timeInForce=GTC&quantity=%s&price=%s&newClientOrderId=%s"
                .formatted(symbol, qty, price, clientOrderId);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(venue.baseUrl() + "/api/v3/order?" + query + "&signature="
                        + VenueSignature.sign(query, FakeBinanceVenue.apiSecret())))
                .header("X-MBX-APIKEY", FakeBinanceVenue.apiKey())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Collects frames and notes when each arrived, so orderings can be asserted rather than assumed. */
    private static final class Recorder implements WebSocket.Listener {

        private final CopyOnWriteArrayList<JsonNode> events = new CopyOnWriteArrayList<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean politely = new AtomicBoolean();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            try {
                events.add(JSON.readTree(data.toString()));
            } catch (Exception e) {
                throw new AssertionError("the stream sent something that is not JSON: " + data, e);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            politely.set(true);
            closed.set(true);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.set(true);
        }

        List<JsonNode> events() {
            return List.copyOf(events);
        }

        boolean closedPolitely() {
            return politely.get();
        }

        void awaitAtLeast(int count) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 10_000;
            while (events.size() < count && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            if (events.size() < count) {
                throw new AssertionError("expected at least %d events, saw %d".formatted(count, events.size()));
            }
        }

        void awaitClosed() throws InterruptedException {
            long deadline = System.currentTimeMillis() + 10_000;
            while (!closed.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
        }
    }
}
