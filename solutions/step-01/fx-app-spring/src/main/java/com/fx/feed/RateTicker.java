package com.fx.feed;

import com.fx.api.model.Rate;
import com.fx.api.repo.RateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The app's own rate feed. There used to be a second app (fx-orchestrator) pushing rate
 * batches over HTTP; that role now lives here as a scheduled in-process job instead — one
 * fewer service, one fewer database, same live-updating rates.
 *
 * Every tick nudges each pair's latest rate by a small random amount and stores it as a new
 * row stamped "now". While ACCEPTING is off the tick is skipped entirely, so — same as the
 * old push-based design — a declined batch leaves no trace in the data at all.
 */
@Component
public class RateTicker {

    private static final Logger log = LoggerFactory.getLogger(RateTicker.class);
    private static final double MAX_STEP = 0.003; // ±0.3% per tick

    private final RateRepository rates;
    private final AcceptingState accepting;

    public RateTicker(RateRepository rates, AcceptingState accepting) {
        this.rates = rates;
        this.accepting = accepting;
    }

    @Scheduled(fixedDelay = 2000)
    void tick() {
        if (!accepting.isOn()) return;

        Instant capturedAt = Instant.now();
        for (Rate current : rates.findLatest()) {
            double next = jitter(current.rate());
            rates.insertTick(current.baseCode(), current.quoteCode(), next, capturedAt);
        }
        log.debug("tick at {}", capturedAt);
    }

    private double jitter(double rate) {
        double pct = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * MAX_STEP;
        double next = rate * (1 + pct);
        return Math.round(next * 10_000.0) / 10_000.0;
    }
}
