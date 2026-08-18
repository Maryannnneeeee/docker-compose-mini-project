package com.fx.orchestrator.feed;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The orchestrator keeps its own history, in its own database, with its own Liquibase
 * changelog. Two services, two schemas — neither one reaches into the other's tables.
 */
@Repository
public class BatchRepository {

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Batch numbers continue across restarts. The counter lives in memory but the history
     * lives in the database, and batch_no is UNIQUE — so on boot we pick up where we left off
     * instead of colliding with rows from the last run.
     */
    public long maxBatchNo() {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(batch_no), 0) FROM feed_batch", Long.class);
        return max == null ? 0L : max;
    }

    public void recordSent(long batchNo, int rateCount) {
        jdbc.update("""
                INSERT INTO feed_batch (batch_no, rate_count, sent_at, status)
                VALUES (?, ?, CURRENT_TIMESTAMP(3), 'PENDING')""",
                batchNo, rateCount);
    }

    public void recordSettled(long batchNo, String status, long latencyMs) {
        jdbc.update("""
                UPDATE feed_batch
                   SET status = ?, latency_ms = ?, settled_at = CURRENT_TIMESTAMP(3)
                 WHERE batch_no = ?""",
                status, latencyMs, batchNo);
    }

    /** Backs `curl localhost:8081/api/feed/batches` — the batch history. */
    public List<Map<String, Object>> recent(int limit) {
        return jdbc.queryForList("""
                SELECT batch_no, rate_count, sent_at, status, latency_ms, settled_at
                  FROM feed_batch
                 ORDER BY batch_no DESC
                 LIMIT ?""", limit);
    }

    public Map<String, Object> summary() {
        return jdbc.queryForMap("""
                SELECT COUNT(*)                                            AS total,
                       SUM(status = 'ACCEPTED')                            AS accepted,
                       SUM(status = 'DECLINED')                            AS declined,
                       SUM(status NOT IN ('ACCEPTED','DECLINED','PENDING')) AS failed
                  FROM feed_batch""");
    }
}
