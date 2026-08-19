package com.fx.core;

/**
 * Checked exception carrying context. Extends FxException (which extends Exception),
 * so it stays checked and a caller can catch either the specific condition or the whole
 * family. The message format is a frozen contract: the tests assert both numbers appear
 * in it.
 */
public class InsufficientFundsException extends FxException {
    private final double requested;
    private final double available;

    public InsufficientFundsException(double requested, double available) {
        super("Requested " + requested + " but only " + available + " available");
        this.requested = requested;
        this.available = available;
    }
    public double getRequested() { return requested; }
    public double getAvailable() { return available; }
}
