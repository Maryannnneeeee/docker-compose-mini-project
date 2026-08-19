package com.fx.core;

/**
 * UNCHECKED on purpose. A non-positive exchange rate is not a
 * business condition a caller should plan for: it is a programming or data bug,
 * like IllegalArgumentException. Compare with InsufficientFundsException, which
 * is checked because an empty wallet is a legitimate everyday outcome.
 */
public class InvalidRateException extends RuntimeException {

    public InvalidRateException(double rate) {
        super("Exchange rate must be positive, got " + rate);
    }
}
