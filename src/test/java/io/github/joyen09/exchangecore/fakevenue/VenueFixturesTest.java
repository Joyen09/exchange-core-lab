package io.github.joyen09.exchangecore.fakevenue;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the fixtures must keep saying.
 *
 * <p>Two of these pin findings that cost real time to discover and would be undone by a well-meaning
 * edit. A fixture is the sort of file somebody "tidies" — adding the field that seems to be missing,
 * replacing the sentinel that looks like a placeholder — and the tidying would look like an
 * improvement right up until production disagreed.
 */
class VenueFixturesTest {

    @Test
    @DisplayName("the execution report keeps its -1 trade id, which is not a placeholder")
    void theNonTradeSentinelSurvives() {
        // A NEW event is not a fill, so the venue sends t = -1 with no commission. This looks exactly
        // like unfinished test data and is the reason processed_trades carries CHECK (trade_id >= 0).
        JsonNode report = VenueFixtures.executionReport();

        assertThat(report.get("x").asText()).as("this fixture is the NEW event").isEqualTo("NEW");
        assertThat(report.get("t").asLong()).as("not a fill, so not a trade id").isEqualTo(-1L);
        assertThat(report.get("N").isNull()).as("and no commission asset either").isTrue();
    }

    @Test
    @DisplayName("no trade object carries a client order id, and none may be added")
    void tradesCarryOnlyTheVenuesOwnOrderId() {
        // Found while building the fake venue: Binance's trade endpoints carry orderId and nothing of
        // ours. TradeSnapshot.clientOrderId is nullable because of this. Adding the field here would
        // make fetchTrades look implementable and leave the real adapter with nothing to read.
        for (JsonNode trade : VenueFixtures.trades()) {
            assertThat(trade.has("orderId")).as("the venue's own id is always there").isTrue();
            assertThat(trade.has("clientOrderId"))
                    .as("a trade object with our id in it did not come from Binance")
                    .isFalse();
            assertThat(trade.has("origClientOrderId")).isFalse();
        }
    }

    @Test
    @DisplayName("the order fixture carries the cumulative figures the design depends on")
    void theOrderCarriesCumulativeFigures() {
        // ADR-0011 rests on the venue reporting totals rather than increments. If these ever stop being
        // present, the ordering argument stops holding and this is where that shows up first.
        JsonNode order = VenueFixtures.order();

        assertThat(order.get("executedQty").asText()).isNotBlank();
        assertThat(order.get("cummulativeQuoteQty").asText())
                .as("Binance's spelling, with the doubled m, is not ours to correct")
                .isNotBlank();
    }

    @Test
    @DisplayName("every fixture is valid JSON that the loader can read")
    void fixturesLoad() {
        assertThat(VenueFixtures.order().isObject()).isTrue();
        assertThat(VenueFixtures.trades().isArray()).isTrue();
        assertThat(VenueFixtures.executionReport().isObject()).isTrue();
        assertThat(VenueFixtures.raw("order.json")).contains("\"symbol\"");
    }
}
