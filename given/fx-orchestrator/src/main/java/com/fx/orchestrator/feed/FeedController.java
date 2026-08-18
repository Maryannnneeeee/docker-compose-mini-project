package com.fx.orchestrator.feed;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The orchestrator's side of the contract.
 *
 *   POST /api/feed/ack        ← fx-app-spring calls this to acknowledge a batch
 *   GET  /api/feed/batches    ← history of what actually happened
 *   GET  /api/feed/summary    ← counts by status
 */
@RestController
@RequestMapping("/api/feed")
public class FeedController {

    private static final Logger log = LoggerFactory.getLogger("FEED");

    private final FeedScheduler scheduler;
    private final BatchRepository batches;

    public FeedController(FeedScheduler scheduler, BatchRepository batches) {
        this.scheduler = scheduler;
        this.batches = batches;
    }

    @PostMapping("/ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ack(@RequestBody AckMessage ack) {
        log.info("│ ← ACK received: batchId={} status={}", ack.batchId(), ack.status());
        scheduler.onAck(ack.batchId(), ack.status());
    }

    @GetMapping("/batches")
    public List<Map<String, Object>> batches(@RequestParam(defaultValue = "20") int limit) {
        return batches.recent(Math.min(limit, 200));
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return batches.summary();
    }
}
