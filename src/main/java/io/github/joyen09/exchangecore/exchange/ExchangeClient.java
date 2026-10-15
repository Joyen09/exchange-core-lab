package io.github.joyen09.exchangecore.exchange;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Everything the rest of the system is allowed to know about a trading venue.
 *
 * <p>Two implementations: {@code BinanceRestClient} for the real testnet, and a fake venue that lives
 * only in {@code src/test}. The division of labour between them is ADR-0009's — behaviour is proven
 * against the fake because three of Phase 3's four acceptance criteria cannot be produced on a real
 * exchange, and protocol is proven against the real testnet by an opt-in test.
 *
 * <h2>The interface leaks no Binance</h2>
 *
 * No error codes, no {@code origClientOrderId}, no {@code executionReport}, no JSON. Binance's numeric
 * codes are translated into {@link RejectionReason} inside the adapter. This is not tidiness: it is what
 * makes the fake venue a substitute rather than a second implementation of Binance. A caller that knew
 * about {@code -2010} would have to be taught about the fake's {@code -2010} too, and the double would
 * stop being a double.
 *
 * <h2>Every method takes a symbol, because every endpoint requires one</h2>
 *
 * {@code symbol} is mandatory on the venue's query, cancel and trade-list endpoints, and on
 * {@code openOrders} it is the difference between a request weighing 6 and one weighing 80
 * ({@code docs/vendor/binance-citations.md} §7, §8). So there is no no-argument {@code fetchOpenOrders}
 * and no {@code fetchOrder(clientOrderId)}: the expensive and the impossible call are both things a
 * caller cannot write, rather than things a comment asks them not to.
 *
 * <p>That is the same principle as the startup endpoint allowlist having no override switch, and as
 * {@code CHECK (trade_id >= 0)} on {@code processed_trades} — see the README. It is applied here at the
 * narrowest level, the signature.
 *
 * <h2>Results and exceptions mean different things</h2>
 *
 * A method returns when the venue gave an answer, including "no". It throws when there is no answer —
 * see {@link ExchangeExceptions}. The submitter depends on that split: a refusal is a state transition,
 * an unreachable venue is a retry, and guessing between them is how an order that is live at the
 * exchange gets marked {@code REJECTED} locally.
 *
 * <h2>Every call is idempotent on the client order id</h2>
 *
 * Submission is at-least-once (ADR-0010), so an implementation must tolerate the same command arriving
 * repeatedly. It must not invent a new identifier per attempt.
 */
public interface ExchangeClient {

    /**
     * Places an order, or reports that it already exists.
     *
     * @return one of {@link PlaceResult.Accepted}, {@link PlaceResult.AlreadyExists} or
     *     {@link PlaceResult.Rejected} — three types rather than one with a flag, so that the duplicate
     *     case cannot be mistaken for a failure by a caller who forgot to check a field
     * @throws ExchangeExceptions.ExchangeUnavailableException if the venue gave no answer
     */
    PlaceResult place(PlaceCommand command);

    /**
     * Cancels an order.
     *
     * @throws ExchangeExceptions.ExchangeUnavailableException if the venue gave no answer
     */
    CancelResult cancel(CancelCommand command);

    /**
     * Reads one order's current state at the venue.
     *
     * <p>Takes the symbol as well as the id because the venue requires both; there is no lookup by
     * client order id alone.
     *
     * @return empty if the venue has no such order, which is a disagreement worth reporting rather than
     *     an error worth throwing
     */
    Optional<OrderSnapshot> fetchOrder(Symbol symbol, ClientOrderId clientOrderId);

    /**
     * Lists the venue's open orders for one symbol.
     *
     * <p>Per symbol, always. The unqualified form of this endpoint costs 80 weight against 6, and the
     * recovery path that calls it runs exactly when the venue is least likely to be healthy — so the
     * cheap form is the only one this interface can express.
     */
    List<OrderSnapshot> fetchOpenOrders(Symbol symbol);

    /**
     * Lists fills for one symbol since a point in time.
     *
     * <p>Also per symbol, and not by choice: the endpoint refuses a request without one
     * ({@code docs/vendor/binance-citations.md} §7). Reconnect recovery therefore costs one call per
     * symbol with open orders, which is what ADR-0011's cost estimate is built from.
     *
     * @param since inclusive lower bound. Callers are expected to overlap it deliberately — ADR-0011
     *     rewinds five minutes, because fetching a trade twice costs a discarded row and missing one
     *     costs a permanent accounting error.
     */
    List<TradeSnapshot> fetchTrades(Symbol symbol, Instant since);
}
