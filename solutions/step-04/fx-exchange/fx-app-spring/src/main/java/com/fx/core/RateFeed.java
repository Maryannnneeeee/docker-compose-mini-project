package com.fx.core;

/** Live market rates come from outside the process — mocked in tests. */
public interface RateFeed {
    /** @return current rate for a pair like "EUR/USD"; throws IllegalArgumentException if unknown. */
    double rateFor(String pair);
}
