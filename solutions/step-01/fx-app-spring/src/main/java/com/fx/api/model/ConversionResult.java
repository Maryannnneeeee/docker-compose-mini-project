package com.fx.api.model;

public record ConversionResult(String pair, double amount, double rate,
                               double converted, double fee, double net) {}
