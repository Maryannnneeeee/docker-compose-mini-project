package com.fx.core;

// The spec for FxRate: construction, guards and formatting.

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FxRateTest {

    private FxRate eurUsd;

    @BeforeEach
    void setUp() {
        // the Week-2 anchor row from fxdb-seed.sql: EUR/USD on 2026-01-12
        eurUsd = new FxRate(Currency.EUR, Currency.USD, 1.0818, "2026-01-12");
    }

    @Test
    @DisplayName("converts at the stored rate (delta assert — doubles are approximate)")
    void convertsAtRate() {
        assertEquals(108.18, eurUsd.convert(100), 0.0001);
    }

    @Test
    @DisplayName("inverted() round-trips: EUR->USD->EUR gives the amount back")
    void invertedRoundTrips() {
        FxRate usdEur = eurUsd.inverted();
        assertEquals(Currency.USD, usdEur.getBase());
        assertEquals(Currency.EUR, usdEur.getQuote());
        assertEquals(100.0, usdEur.convert(eurUsd.convert(100)), 0.0001);
    }

    @Test
    @DisplayName("a non-positive rate is a bug, not a market fact")
    void rejectsNonPositiveRate() {
        assertThrows(InvalidRateException.class,
                () -> new FxRate(Currency.EUR, Currency.USD, 0.0, "2026-01-12"));
        assertThrows(InvalidRateException.class,
                () -> new FxRate(Currency.EUR, Currency.USD, -1.0818, "2026-01-12"));
    }

    @Test
    @DisplayName("toString reads like the fx_rate row it mirrors")
    void toStringFormat() {
        assertTrue(eurUsd.toString().startsWith("EUR/USD 1.0818"),
                "expected EUR/USD 1.0818 (2026-01-12) but got: " + eurUsd);
    }
}
