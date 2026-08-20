package com.fx.core;

/**
 * The Java shape of the `fx_rate` table: base_code / quote_code / rate / rate_date.
 * These rows are what the REST API serves — the reference value EUR/USD 1.0818
 * (2026-01-12) comes from the Liquibase seed.
 */
public class FxRate {
    private final Currency base;       // mirrors fx_rate.base_code
    private final Currency quote;      // mirrors fx_rate.quote_code
    private final double rate;         // mirrors fx_rate.rate
    private final String rateDate;     // mirrors fx_rate.rate_date (ISO yyyy-MM-dd)

    public FxRate(Currency base, Currency quote, double rate, String rateDate) {
        if (rate <= 0) throw new InvalidRateException(rate);
        this.base = base;
        this.quote = quote;
        this.rate = rate;
        this.rateDate = rateDate;
    }

    public Currency getBase() { return base; }
    public Currency getQuote() { return quote; }
    public double getRate() { return rate; }
    public String getRateDate() { return rateDate; }

    /** 100 base becomes how much quote? */
    public double convert(double amount) { return amount * rate; }

    /** The same market fact read the other way: EUR/USD 1.0818 -> USD/EUR (1/1.0818). */
    public FxRate inverted() { return new FxRate(quote, base, 1.0 / rate, rateDate); }

    @Override public String toString() {
        return String.format("%s/%s %.4f (%s)", base, quote, rate, rateDate);
    }
}
