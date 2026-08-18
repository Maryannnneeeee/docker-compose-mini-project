package com.fx.orchestrator.feed;

import java.time.Instant;
import java.util.List;

/**
 * The body POSTed downstream: POST {fx-app-spring}/api/feed/rates
 *
 * {
 *   "batchId": 12,
 *   "generatedAt": "2026-07-21T09:31:04.220Z",
 *   "rates": [ {"base":"EUR","quote":"USD","rate":1.0837}, ... ]
 * }
 */
public record FeedMessage(long batchId, Instant generatedAt, List<RateTick> rates) {}
