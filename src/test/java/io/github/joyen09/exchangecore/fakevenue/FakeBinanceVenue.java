package io.github.joyen09.exchangecore.fakevenue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An in-process Binance Spot REST subset, scripted and seeded.
 *
 * <p><b>It is not a mock server.</b> A mock server invents its responses; this one invents no bytes.
 * Every reply is a recorded payload with values substituted into fields the fixture already has — see
 * {@link VenueResponse}, which throws rather than add a field. What this class owns is <em>timing and
 * state</em>: which attempt fails, what the order looks like after a fill, whether the socket is still
 * there. Those are the things no recording can supply, and they are why ADR-0009 rejected
 * record-and-replay as a source of scenarios while adopting it as a source of bytes.
 *
 * <p>The practical consequence: a protocol misunderstanding cannot hide in the gap between this class
 * and the adapter, because there is no gap. They read the same fixture.
 *
 * <h2>Signatures are really verified</h2>
 *
 * HMAC-SHA256 over the exact query string, with {@code -1022} for a mismatch. Faking this away is the
 * obvious economy and the wrong one: request signing is the single part of the adapter most likely to
 * be subtly wrong, and skipping it here would leave it exercised only by a test that is skipped by
 * default.
 */
public final class FakeBinanceVenue implements AutoCloseable {

    /** The venue's own view of an order. Deliberately not the adapter's type. */
    record VenueOrder(
            String symbol,
            String clientOrderId,
            long orderId,
            String side,
            String type,
            String timeInForce,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal executedQuantity,
            BigDecimal cumulativeQuote,
            String status,
            long createdAt,
            long updatedAt) {

        VenueOrder withFill(BigDecimal fillQuantity, BigDecimal fillPrice, long at) {
            BigDecimal filled = executedQuantity.add(fillQuantity);
            BigDecimal quote = cumulativeQuote.add(fillQuantity.multiply(fillPrice));
            String next = filled.compareTo(quantity) >= 0 ? "FILLED" : "PARTIALLY_FILLED";
            return new VenueOrder(
                    symbol, clientOrderId, orderId, side, type, timeInForce, quantity, price,
                    filled, quote, next, createdAt, at);
        }

        boolean isTerminal() {
            return status.equals("FILLED") || status.equals("CANCELED") || status.equals("EXPIRED")
                    || status.equals("REJECTED");
        }
    }

    /** The venue's own view of a fill. */
    record VenueTrade(
            String symbol, long tradeId, long orderId, BigDecimal quantity, BigDecimal price, long at) {}

    private static final String API_KEY = "fake-venue-api-key";
    private static final String API_SECRET = "fake-venue-api-secret";

    private final HttpServer server;
    private final VenueScript script;
    private final Map<String, VenueOrder> ordersByClientOrderId = new LinkedHashMap<>();
    private final List<VenueTrade> trades = new ArrayList<>();
    private final AtomicLong orderIds = new AtomicLong(12_569_099_453L);
    private final AtomicLong tradeIds = new AtomicLong(1_650_422_481L);
    private final AtomicLong clock = new AtomicLong(1_660_801_715_639L);
    private final AtomicInteger usedWeight = new AtomicInteger();

    public FakeBinanceVenue(VenueScript script) {
        this.script = script;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the fake venue", e);
        }
        server.createContext("/api/v3/order", this::handleOrder);
        server.createContext("/api/v3/openOrders", this::handleOpenOrders);
        server.createContext("/api/v3/myTrades", this::handleMyTrades);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public static String apiKey() {
        return API_KEY;
    }

    public static String apiSecret() {
        return API_SECRET;
    }

    /** Applies the script's steps for an order — the fills a test wants to have happened. */
    public void runScriptFor(String clientOrderId) {
        for (VenueScript.Step step : script.stepsFor(clientOrderId)) {
            if (step instanceof VenueScript.Step.Fill fill) {
                applyFill(clientOrderId, fill.quantity(), fill.price());
            }
            // The stream-affecting steps belong to the WebSocket half and are ignored here.
        }
    }

    /**
     * Records a fill in the venue's own state.
     *
     * <p>Public because acceptance criterion 1 depends on it: a fill that happens while the client is
     * disconnected must still be <em>here</em>, so that the only way to learn about it is to reconnect
     * and ask. A fake that skipped the fill while nobody was listening would make the recovery path
     * untestable by making there be nothing to recover.
     */
    public synchronized void applyFill(String clientOrderId, BigDecimal quantity, BigDecimal price) {
        VenueOrder order = ordersByClientOrderId.get(clientOrderId);
        if (order == null) {
            throw new IllegalStateException("no such order at the venue: " + clientOrderId);
        }
        long at = clock.addAndGet(1_000);
        ordersByClientOrderId.put(clientOrderId, order.withFill(quantity, price, at));
        trades.add(new VenueTrade(
                order.symbol(), tradeIds.getAndIncrement(), order.orderId(), quantity, price, at));
    }

    public synchronized VenueOrder orderOf(String clientOrderId) {
        return ordersByClientOrderId.get(clientOrderId);
    }

    public synchronized List<VenueTrade> tradesOf(String symbol) {
        return trades.stream().filter(t -> t.symbol().equals(symbol)).toList();
    }

    public int usedWeight() {
        return usedWeight.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---------------------------------------------------------------------------------------------
    // Routing
    // ---------------------------------------------------------------------------------------------

    private void handleOrder(HttpExchange exchange) throws IOException {
        switch (exchange.getRequestMethod()) {
            case "POST" -> guarded(exchange, "POST /api/v3/order", 1, this::place);
            case "GET" -> guarded(exchange, "GET /api/v3/order", 4, this::queryOrder);
            case "DELETE" -> guarded(exchange, "DELETE /api/v3/order", 1, this::cancel);
            default -> error(exchange, 405, -1121, "Method not allowed.");
        }
    }

    private void handleOpenOrders(HttpExchange exchange) throws IOException {
        // Weight 6 with a symbol, 80 without — the asymmetry ExchangeClient's signature is built around.
        Map<String, String> query = queryOf(exchange);
        int weight = query.containsKey("symbol") ? 6 : 80;
        guarded(exchange, "GET /api/v3/openOrders", weight, this::openOrders);
    }

    private void handleMyTrades(HttpExchange exchange) throws IOException {
        guarded(exchange, "GET /api/v3/myTrades", 20, this::myTrades);
    }

    /** Everything every authenticated route must do before it does anything of its own. */
    private void guarded(HttpExchange exchange, String route, int weight, Handler handler) throws IOException {
        Integer scriptedFailure = script.nextFailureFor(route);
        if (scriptedFailure != null) {
            // Before the signature check: a 503 is the venue being unwell, not a rejection, and the
            // adapter must treat it as "no answer" rather than "no".
            error(exchange, scriptedFailure, -1001, "Internal error; unable to process your request.");
            return;
        }

        int used = usedWeight.addAndGet(weight);
        exchange.getResponseHeaders().add("X-MBX-USED-WEIGHT-1M", Integer.toString(used));
        if (used > script.weightLimit()) {
            exchange.getResponseHeaders().add("Retry-After", "60");
            error(exchange, 429, -1003, "Too many requests; current limit is "
                    + script.weightLimit() + " request weight per 1 MINUTE.");
            return;
        }

        if (!VenueSignature.isValid(exchange, API_KEY, API_SECRET)) {
            error(exchange, 401, -1022, "Signature for this request is not valid.");
            return;
        }

        handler.handle(exchange, queryOf(exchange));
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange, Map<String, String> query) throws IOException;
    }

    // ---------------------------------------------------------------------------------------------
    // Endpoints
    // ---------------------------------------------------------------------------------------------

    private synchronized void place(HttpExchange exchange, Map<String, String> query) throws IOException {
        String clientOrderId = query.get("newClientOrderId");
        if (clientOrderId == null || clientOrderId.isBlank()) {
            error(exchange, 400, -1102, "Mandatory parameter 'newClientOrderId' was not sent, was empty/null, or malformed.");
            return;
        }
        if (ordersByClientOrderId.containsKey(clientOrderId)) {
            // -2010 with this exact message, which the adapter must not branch on: the same code also
            // means insufficient balance (docs/vendor/binance-citations.md §2).
            error(exchange, 400, -2010, "Duplicate order sent.");
            return;
        }

        long at = clock.addAndGet(1_000);
        VenueOrder order = new VenueOrder(
                query.get("symbol"),
                clientOrderId,
                orderIds.getAndIncrement(),
                query.get("side"),
                query.get("type"),
                query.getOrDefault("timeInForce", "GTC"),
                new BigDecimal(query.getOrDefault("quantity", "0")),
                new BigDecimal(query.getOrDefault("price", "0")),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "NEW",
                at,
                at);
        ordersByClientOrderId.put(clientOrderId, order);
        respond(exchange, 200, render(order).json());
    }

    private synchronized void queryOrder(HttpExchange exchange, Map<String, String> query) throws IOException {
        VenueOrder order = ordersByClientOrderId.get(query.get("origClientOrderId"));
        if (order == null || !order.symbol().equals(query.get("symbol"))) {
            error(exchange, 400, -2013, "Order does not exist.");
            return;
        }
        respond(exchange, 200, render(order).json());
    }

    private synchronized void cancel(HttpExchange exchange, Map<String, String> query) throws IOException {
        VenueOrder order = ordersByClientOrderId.get(query.get("origClientOrderId"));
        if (order == null) {
            error(exchange, 400, -2011, "Unknown order sent.");
            return;
        }
        if (order.isTerminal()) {
            // The venue refuses what the documentation says it refuses. A double that accepted this
            // would never teach the adapter to handle the refusal — ADR-0009's second way to be fooled.
            error(exchange, 400, -2011, "Unknown order sent.");
            return;
        }
        VenueOrder canceled = new VenueOrder(
                order.symbol(), order.clientOrderId(), order.orderId(), order.side(), order.type(),
                order.timeInForce(), order.quantity(), order.price(), order.executedQuantity(),
                order.cumulativeQuote(), "CANCELED", order.createdAt(), clock.addAndGet(1_000));
        ordersByClientOrderId.put(order.clientOrderId(), canceled);
        respond(exchange, 200, render(canceled).json());
    }

    private synchronized void openOrders(HttpExchange exchange, Map<String, String> query) throws IOException {
        String symbol = query.get("symbol");
        List<String> rendered = ordersByClientOrderId.values().stream()
                .filter(order -> symbol == null || order.symbol().equals(symbol))
                .filter(order -> !order.isTerminal())
                .map(order -> render(order).json())
                .toList();
        respond(exchange, 200, "[" + String.join(",", rendered) + "]");
    }

    private synchronized void myTrades(HttpExchange exchange, Map<String, String> query) throws IOException {
        String symbol = query.get("symbol");
        if (symbol == null) {
            error(exchange, 400, -1102, "Mandatory parameter 'symbol' was not sent, was empty/null, or malformed.");
            return;
        }
        long since = Long.parseLong(query.getOrDefault("startTime", "0"));
        List<String> rendered = trades.stream()
                .filter(trade -> trade.symbol().equals(symbol) && trade.at() >= since)
                .map(trade -> renderTrade(trade).json())
                .toList();
        respond(exchange, 200, "[" + String.join(",", rendered) + "]");
    }

    // ---------------------------------------------------------------------------------------------
    // Rendering — values into a recorded shape, never a new field
    // ---------------------------------------------------------------------------------------------

    private VenueResponse render(VenueOrder order) {
        return VenueResponse.from(VenueFixtures.order())
                .with("symbol", order.symbol())
                .with("orderId", order.orderId())
                .with("clientOrderId", order.clientOrderId())
                .with("price", order.price().toPlainString())
                .with("origQty", order.quantity().toPlainString())
                .with("executedQty", order.executedQuantity().toPlainString())
                .with("cummulativeQuoteQty", order.cumulativeQuote().toPlainString())
                .with("status", order.status())
                .with("timeInForce", order.timeInForce())
                .with("type", order.type())
                .with("side", order.side())
                .with("time", order.createdAt())
                .with("updateTime", order.updatedAt())
                .with("isWorking", !order.isTerminal())
                .with("workingTime", order.createdAt());
    }

    private VenueResponse renderTrade(VenueTrade trade) {
        return VenueResponse.from(VenueFixtures.trades().get(0))
                .with("symbol", trade.symbol())
                .with("id", trade.tradeId())
                .with("orderId", trade.orderId())
                .with("price", trade.price().toPlainString())
                .with("qty", trade.quantity().toPlainString())
                .with("quoteQty", trade.quantity().multiply(trade.price()).toPlainString())
                .with("time", trade.at());
    }

    // ---------------------------------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------------------------------

    private static Map<String, String> queryOf(HttpExchange exchange) {
        Map<String, String> parsed = new LinkedHashMap<>();
        URI uri = exchange.getRequestURI();
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return parsed;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                parsed.put(
                        java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return parsed;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void error(HttpExchange exchange, int status, int code, String message) throws IOException {
        respond(exchange, status, "{\"code\":%d,\"msg\":\"%s\"}".formatted(code, message));
    }
}
