package io.github.joyen09.exchangecore.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The transition table, all 64 cells of it.
 *
 * <p>The expectations are written out here as their own data rather than derived from
 * {@link OrderStatusTransitions} — a test that asks the implementation what it allows and then asserts
 * that it allows it would pass against any table at all, which is the failure mode this repository keeps
 * finding. These sets come from the specification; if the two disagree, one of them is wrong and the
 * test says so.
 */
class OrderStatusTransitionsTest {

    /** Straight from SPEC §2.2. Changing the implementation must mean changing this too. */
    private static final Map<OrderStatus, Set<OrderStatus>> EXPECTED = Map.of(
            OrderStatus.PENDING,
                    Set.of(OrderStatus.SUBMITTED, OrderStatus.CANCELING, OrderStatus.CANCELED, OrderStatus.REJECTED),
            OrderStatus.SUBMITTED,
                    Set.of(
                            OrderStatus.PARTIALLY_FILLED,
                            OrderStatus.CANCELING,
                            OrderStatus.FILLED,
                            OrderStatus.CANCELED,
                            OrderStatus.REJECTED,
                            OrderStatus.EXPIRED),
            OrderStatus.PARTIALLY_FILLED,
                    Set.of(
                            OrderStatus.PARTIALLY_FILLED,
                            OrderStatus.CANCELING,
                            OrderStatus.FILLED,
                            OrderStatus.CANCELED,
                            OrderStatus.EXPIRED),
            OrderStatus.CANCELING,
                    Set.of(
                            OrderStatus.PARTIALLY_FILLED,
                            OrderStatus.FILLED,
                            OrderStatus.CANCELED,
                            OrderStatus.EXPIRED),
            OrderStatus.FILLED, Set.of(),
            OrderStatus.CANCELED, Set.of(),
            OrderStatus.REJECTED, Set.of(),
            OrderStatus.EXPIRED, Set.of());

    @TestFactory
    @DisplayName("every cell of the 8x8 transition matrix")
    Stream<DynamicTest> everyCellOfTheMatrix() {
        List<DynamicTest> cells = Stream.of(OrderStatus.values())
                .flatMap(from -> Stream.of(OrderStatus.values()).map(to -> DynamicTest.dynamicTest(
                        "%s -> %s".formatted(from, to), () -> assertCell(from, to))))
                .toList();

        assertThat(cells).as("8 states squared").hasSize(64);
        return cells.stream();
    }

    private static void assertCell(OrderStatus from, OrderStatus to) {
        boolean expected = EXPECTED.get(from).contains(to);
        UUID orderId = UUID.randomUUID();

        assertThat(OrderStatusTransitions.isLegal(from, to))
                .as("%s -> %s should be %s", from, to, expected ? "legal" : "refused")
                .isEqualTo(expected);

        if (expected) {
            assertThatCode(() -> OrderStatusTransitions.verify(orderId, from, to)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> OrderStatusTransitions.verify(orderId, from, to))
                    .isInstanceOf(IllegalStateTransitionException.class)
                    .hasMessageContaining(orderId.toString())
                    .hasMessageContaining(from.name())
                    .hasMessageContaining(to.name());
        }
    }

    @Test
    @DisplayName("a terminal state has no way out, for any target at all")
    void terminalStatesAreFinal() {
        for (OrderStatus terminal : OrderStatus.values()) {
            if (!terminal.isTerminal()) {
                continue;
            }
            assertThat(OrderStatusTransitions.targetsFrom(terminal))
                    .as("%s is terminal", terminal)
                    .isEmpty();
            for (OrderStatus to : OrderStatus.values()) {
                assertThatThrownBy(() -> OrderStatusTransitions.verify(UUID.randomUUID(), terminal, to))
                        .as("%s -> %s", terminal, to)
                        .isInstanceOf(IllegalStateTransitionException.class);
            }
        }
    }

    @Test
    @DisplayName("exactly four states are terminal")
    void fourTerminalStates() {
        assertThat(Stream.of(OrderStatus.values()).filter(OrderStatus::isTerminal).toList())
                .containsExactlyInAnyOrder(
                        OrderStatus.FILLED, OrderStatus.CANCELED, OrderStatus.REJECTED, OrderStatus.EXPIRED);
    }

    @Test
    @DisplayName("the table is immutable from outside")
    void tableCannotBeMutated() {
        // A caller that could add an edge would make the matrix test above a statement about history.
        assertThatThrownBy(() -> OrderStatusTransitions.targetsFrom(OrderStatus.PENDING).add(OrderStatus.FILLED))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
