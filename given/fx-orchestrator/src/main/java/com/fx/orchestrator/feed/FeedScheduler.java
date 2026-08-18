package com.fx.orchestrator.feed;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * The loop, and the whole contract:
 *
 *   1. POST a batch of fresh rates to  {fx.target.url}/api/feed/rates
 *   2. Wait for the ACK                POST /api/feed/ack  {"batchId":N,"status":"..."}
 *   3. ACCEPTED → send the next batch in 2s.  DECLINED (or silence) → wait 10s.
 *
 * Exactly one batch is ever in flight. Every decision is logged, loudly, with the reason.
 */
@Component
public class FeedScheduler {

    private static final Logger log = LoggerFactory.getLogger("FEED");
    private static final String LINE =
            "──────────────────────────────────────────────────────────────────────────";

    private final RestTemplate http;
    private final RateGenerator generator;
    private final BatchRepository batches;

    private final String targetUrl;
    private final String selfUrl;
    private final Duration acceptedDelay;
    private final Duration declinedDelay;
    private final Duration unreachableDelay;
    private final Duration ackTimeout;

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fx-feed");
        t.setDaemon(true);
        return t;
    });

    private final AtomicLong batchNo = new AtomicLong();
    private final AtomicBoolean settled = new AtomicBoolean(true);
    private volatile long pendingBatch = -1;
    private volatile long sentAtNanos;

    public FeedScheduler(RestTemplate http,
                         RateGenerator generator,
                         BatchRepository batches,
                         @Value("${fx.target.url}") String targetUrl,
                         @Value("${fx.self.url}") String selfUrl,
                         @Value("${fx.delay.accepted:2s}") Duration acceptedDelay,
                         @Value("${fx.delay.declined:10s}") Duration declinedDelay,
                         @Value("${fx.delay.unreachable:5s}") Duration unreachableDelay,
                         @Value("${fx.ack.timeout:5s}") Duration ackTimeout) {
        this.http = http;
        this.generator = generator;
        this.batches = batches;
        this.targetUrl = trimSlash(targetUrl);
        this.selfUrl = trimSlash(selfUrl);
        this.acceptedDelay = acceptedDelay;
        this.declinedDelay = declinedDelay;
        this.unreachableDelay = unreachableDelay;
        this.ackTimeout = ackTimeout;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        log.info("");
        log.info("╔{}╗", LINE);
        log.info("║ fx-orchestrator is up. The contract:");
        log.info("║");
        log.info("║   I POST rate batches to : {}/api/feed/rates", targetUrl);
        log.info("║   ACKs come back to      : {}/api/feed/ack", selfUrl);
        log.info("║");
        log.info("║   ACCEPTED  → I send the next batch in {}s", acceptedDelay.toSeconds());
        log.info("║   DECLINED  → I back off and wait {}s", declinedDelay.toSeconds());
        log.info("║   no ACK in {}s → I assume DECLINED", ackTimeout.toSeconds());
        log.info("╚{}╝", LINE);
        log.info("");

        // Resume numbering after a restart; the DB remembers even though this process doesn't.
        try {
            long resumeFrom = batches.maxBatchNo();
            batchNo.set(resumeFrom);
            if (resumeFrom > 0) {
                log.info("Resuming after batch #{} (from orchestrator-db).", resumeFrom);
            }
        } catch (RuntimeException e) {
            log.warn("Could not read the last batch number, starting at 1: {}", rootCause(e));
        }

        exec.schedule(this::sendBatch, 3, TimeUnit.SECONDS);
    }

    private void sendBatch() {
        long no = batchNo.incrementAndGet();
        List<RateTick> ticks = generator.nextBatch();

        pendingBatch = no;
        settled.set(false);
        sentAtNanos = System.nanoTime();
        safeRecordSent(no, ticks.size());

        log.info("┌{}", LINE);
        log.info("│ BATCH #{}  →  POST {}/api/feed/rates", no, targetUrl);
        log.info("│ {} fresh rates.  {}", ticks.size(), sample(ticks));
        log.info("│ Now waiting up to {}s for the ACK:", ackTimeout.toSeconds());
        log.info("│     POST {}/api/feed/ack", selfUrl);
        // The JSON goes through as an ARGUMENT: SLF4J only reads {} in the format string,
        // so the braces survive instead of being eaten as placeholders.
        log.info("│     {}   ← batch stored",     "{\"batchId\":" + no + ",\"status\":\"ACCEPTED\"}");
        log.info("│     {}   ← ACCEPTING is OFF", "{\"batchId\":" + no + ",\"status\":\"DECLINED\"}");

        try {
            http.postForEntity(targetUrl + "/api/feed/rates",
                    new FeedMessage(no, Instant.now(), ticks), Void.class);
            log.info("│ POST accepted by fx-app-spring. Awaiting the ACK.");
            exec.schedule(() -> onAckTimeout(no), ackTimeout.toMillis(), TimeUnit.MILLISECONDS);

        } catch (HttpStatusCodeException e) {
            int code = e.getStatusCode().value();
            log.warn("│ !! fx-app-spring answered HTTP {} — it did not take the batch.", code);
            if (code == 404) {
                log.warn("│    Nothing is mapped at POST /api/feed/rates.");
                log.warn("│    It must accept the batch and answer 202.");
            } else if (code == 415 || code == 400) {
                log.warn("│    The endpoint exists but rejected the body. It must accept");
                log.warn("│    @RequestBody JSON shaped like: {}",
                        "{\"batchId\":N,\"generatedAt\":\"...\",\"rates\":[...]}");
            }
            settle(no, "HTTP_" + code, declinedDelay, "the POST was rejected");

        } catch (ResourceAccessException e) {
            log.warn("│ !! Could not reach fx-app-spring at {}", targetUrl);
            log.warn("│    {}", rootCause(e));
            log.warn("│    Is the container up? Is fx.target.url the compose SERVICE NAME");
            log.warn("│    (http://fx-app-spring:8080) rather than localhost?");
            settle(no, "UNREACHABLE", unreachableDelay, "nobody answered");

        } catch (RuntimeException e) {
            log.error("│ !! Unexpected failure sending batch #{}: {}", no, rootCause(e));
            settle(no, "ERROR", declinedDelay, "an unexpected error");
        }
    }

    /** Called from the web layer when the downstream app ACKs. */
    public void onAck(long batchId, String rawStatus) {
        String status = "ACCEPTED".equalsIgnoreCase(rawStatus) ? "ACCEPTED" : "DECLINED";

        if (batchId != pendingBatch) {
            log.warn("│ ?? ACK for batch #{} but the batch in flight is #{}. Ignoring.", batchId, pendingBatch);
            log.warn("│    The ACK must echo back the SAME batchId that was sent.");
            return;
        }

        Duration next = "ACCEPTED".equals(status) ? acceptedDelay : declinedDelay;
        settle(batchId, status, next, "ACCEPTED".equals(status)
                ? "the batch was stored"
                : "the downstream app is not accepting right now");
    }

    private void onAckTimeout(long no) {
        if (no != pendingBatch || settled.get()) {
            return;   // already answered — nothing to do
        }
        log.warn("│ !! NO ACK for batch #{} within {}s.", no, ackTimeout.toSeconds());
        log.warn("│    Nothing called back to {}.", selfUrl);
        log.warn("│    Check fx-app-spring's log — did the callback throw?");
        settle(no, "NO_ACK", declinedDelay, "nothing called back");
    }

    /** Settles the in-flight batch exactly once, then schedules the next one. */
    private void settle(long no, String status, Duration next, String because) {
        if (no != pendingBatch || !settled.compareAndSet(false, true)) {
            return;
        }
        long latencyMs = Math.round((System.nanoTime() - sentAtNanos) / 1_000_000.0);
        safeRecordSettled(no, status, latencyMs);

        String mark = "ACCEPTED".equals(status) ? "✓" : "✗";
        log.info("│ {} batch #{}  {}  ({} ms) — {}", mark, no, status, latencyMs, because);
        log.info("│ → next batch in {}s", next.toSeconds());
        log.info("└{}", LINE);
        log.info("");

        exec.schedule(this::sendBatch, next.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        exec.shutdownNow();
    }

    /* ----------------------------------------------------------------- util */

    private static String sample(List<RateTick> ticks) {
        return ticks.stream().limit(3)
                .map(t -> "%s/%s %s".formatted(t.base(), t.quote(), t.rate()))
                .reduce((a, b) -> a + "   " + b)
                .orElse("");
    }

    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /* The feed must keep running even if its own database hiccups. */
    private void safeRecordSent(long no, int count) {
        try {
            batches.recordSent(no, count);
        } catch (RuntimeException e) {
            log.warn("│ (could not write batch #{} to orchestrator-db: {})", no, rootCause(e));
        }
    }

    private void safeRecordSettled(long no, String status, long latencyMs) {
        try {
            batches.recordSettled(no, status, latencyMs);
        } catch (RuntimeException e) {
            log.warn("│ (could not update batch #{} in orchestrator-db: {})", no, rootCause(e));
        }
    }
}
