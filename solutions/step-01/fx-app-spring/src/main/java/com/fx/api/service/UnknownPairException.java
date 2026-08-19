package com.fx.api.service;

public class UnknownPairException extends RuntimeException {
    public UnknownPairException(String pair) { super("No rate available for pair " + pair); }
}
