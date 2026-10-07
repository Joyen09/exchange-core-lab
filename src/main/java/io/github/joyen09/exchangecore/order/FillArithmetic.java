package io.github.joyen09.exchangecore.order;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * The arithmetic of accumulating fills, in one place.
 *
 * <p>Shared by the write path and by {@link OrderProjector} on purpose: if the two computed average
 * price differently, the projection would disagree with the log and the property test in §7.4 would be
 * the only thing that ever noticed.
 */
public final class FillArithmetic {

    /** Money is NUMERIC(36,18) in the database, so every derived amount is normalised to that scale. */
    public static final int MONEY_SCALE = 18;

    private FillArithmetic() {}

    /** Volume-weighted average. {@code null} when nothing has filled, matching the column. */
    public static BigDecimal averagePrice(BigDecimal totalNotional, BigDecimal totalQuantity) {
        if (totalQuantity == null || totalQuantity.signum() == 0) {
            return null;
        }
        return normalise(totalNotional.divide(totalQuantity, MathContext.DECIMAL128));
    }

    /** Rounds to the stored scale so a value read back from the database compares equal. */
    public static BigDecimal normalise(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
