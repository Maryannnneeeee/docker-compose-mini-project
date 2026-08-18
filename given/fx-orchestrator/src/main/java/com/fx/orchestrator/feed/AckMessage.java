package com.fx.orchestrator.feed;

/**
 * The body posted back to: POST {fx-orchestrator}/api/feed/ack
 *
 *   {"batchId": 12, "status": "ACCEPTED"}   → next batch in 2s
 *   {"batchId": 12, "status": "DECLINED"}   → next batch in 10s
 *
 * Anything other than ACCEPTED is treated as DECLINED.
 */
public record AckMessage(long batchId, String status) {}
