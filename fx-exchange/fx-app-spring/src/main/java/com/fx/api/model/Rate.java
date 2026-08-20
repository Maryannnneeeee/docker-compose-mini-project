package com.fx.api.model;

import java.time.Instant;
import java.time.LocalDate;

/** Row of the fx_rate table. Records: immutable, concise. */
public record Rate(int id, String baseCode, String quoteCode, double rate, LocalDate rateDate, Instant capturedAt) {
    public String pair() { return baseCode + "/" + quoteCode; }
}
