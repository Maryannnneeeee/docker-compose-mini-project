package com.fx.orchestrator.feed;

/**
 * One rate in a batch. This is the shape fx-app-spring accepts:
 * {"base":"EUR","quote":"USD","rate":1.0837}
 */
public record RateTick(String base, String quote, double rate) {}
