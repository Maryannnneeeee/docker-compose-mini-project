package com.fx.core;

/** Converts using a live RateFeed. The collaborator makes this class Mockito material. */
public class ConversionService {
    private final RateFeed feed;

    public ConversionService(RateFeed feed) { this.feed = feed; }

    public double convert(String pair, double amount) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        return amount * feed.rateFor(pair);
    }

    /** Buys at feed rate + spread (in basis points). */
    public double convertWithSpread(String pair, double amount, int spreadBps) {
        double rate = feed.rateFor(pair) * (1 + spreadBps / 10_000.0);
        return amount * rate;
    }
}
