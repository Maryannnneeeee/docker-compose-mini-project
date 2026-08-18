package com.fx.orchestrator.feed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

/**
 * Makes up fresh rates. Every batch is genuinely different — this is a market, not a fixture.
 *
 * The ten pairs and their opening values are exactly the ones seeded into fxdb for
 * 2026-01-12, so the first batch lands close to the seeded values (EUR/USD 1.0818)
 * and then wanders from there.
 */
@Component
public class RateGenerator {

    /** Opening values = the 2026-01-12 rows in the fxdb seed. */
    private static final Map<String, Double> OPENING = new LinkedHashMap<>(Map.of(
            "USD/JPY", 147.6026,
            "USD/CHF", 0.8755,
            "USD/CAD", 1.3517,
            "USD/SGD", 1.3336,
            "EUR/USD", 1.0818,
            "EUR/GBP", 0.8568,
            "EUR/JPY", 159.8777,
            "GBP/USD", 1.2629,
            "GBP/JPY", 190.2747,
            "AUD/USD", 0.6601));

    /** Insertion-ordered so the log and the table always list pairs the same way. */
    private static final List<String> PAIRS = List.of(
            "USD/JPY", "USD/CHF", "USD/CAD", "USD/SGD", "EUR/USD",
            "EUR/GBP", "EUR/JPY", "GBP/USD", "GBP/JPY", "AUD/USD");

    private final Map<String, Double> current = new LinkedHashMap<>(OPENING);

    /** A random walk of at most ±0.35% per tick — enough to see, small enough to look real. */
    public List<RateTick> nextBatch() {
        List<RateTick> ticks = new ArrayList<>(PAIRS.size());
        for (String pair : PAIRS) {
            double last = current.get(pair);
            double drift = ThreadLocalRandom.current().nextDouble(-0.0035, 0.0035);
            double next = round4(last * (1 + drift));

            // Keep it anchored: never let a walk run more than 5% from where it opened.
            double open = OPENING.get(pair);
            next = Math.max(round4(open * 0.95), Math.min(round4(open * 1.05), next));

            current.put(pair, next);
            String[] sides = pair.split("/");
            ticks.add(new RateTick(sides[0], sides[1], next));
        }
        return ticks;
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
