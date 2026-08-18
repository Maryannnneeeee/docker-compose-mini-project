package com.fx.core;

/** Tiered conversion fees, with a floor per customer type. */
public class FeeCalculator {

    public double feeFor(double amount, boolean businessCustomer) {
        if (amount < 0) throw new IllegalArgumentException("negative amount");
        double pct = businessCustomer ? 0.25 : tierPct(amount);
        double fee = amount * pct / 100.0;
        double min = businessCustomer ? 5.00 : 1.00;
        return Math.max(round2(fee), min);
    }

    private double tierPct(double amount) {
        if (amount < 1_000) return 1.0;
        if (amount < 10_000) return 0.5;
        return 0.25;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
