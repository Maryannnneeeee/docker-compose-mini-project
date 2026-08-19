package com.fx.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the fee tiers, the minimum-fee floor and the business-customer rate. */
class FeeCalculatorTest {

    private final FeeCalculator calc = new FeeCalculator();

    @Test void step1_retailSmallAmountPaysOnePercent() {
        assertEquals(5.00, calc.feeFor(500, false), 1e-9);
    }

    @Test void step2_retailMidTierPaysHalfPercent() {
        assertEquals(25.00, calc.feeFor(5_000, false), 1e-9);
    }

    @Test void step3_retailLargePaysQuarterPercent() {
        assertEquals(125.00, calc.feeFor(50_000, false), 1e-9);
    }

    @Test void step4_minimumFeeKicksIn() {
        assertEquals(1.00, calc.feeFor(50, false), 1e-9);   // 0.50 would be below min
    }

    @Test void step5_businessFlatQuarterPercentWithHigherMinimum() {
        assertEquals(5.00, calc.feeFor(1_000, true), 1e-9); // 2.50 -> min 5.00
        assertEquals(125.00, calc.feeFor(50_000, true), 1e-9);
    }

    @Test void step6_negativeAmountRejected() {
        assertThrows(IllegalArgumentException.class, () -> calc.feeFor(-1, false));
    }

    @Test void step7_boundaryExactlyAtTierEdge() {
        assertEquals(5.00, calc.feeFor(1_000, false), 1e-9);   // 1000 is MID tier (0.5%)
        assertEquals(25.00, calc.feeFor(10_000, false), 1e-9); // 10000 is LARGE tier
    }
}
