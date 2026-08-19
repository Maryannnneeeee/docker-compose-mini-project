package com.fx.api.service;

import com.fx.api.model.ConversionResult;
import com.fx.api.model.Rate;
import com.fx.api.repo.RateRepository;
import com.fx.core.FeeCalculator;
import org.springframework.stereotype.Service;

/** The layered architecture's middle: web -> service -> repo. */
@Service
public class ConversionService {

    private final RateRepository rates;
    private final FeeCalculator fees = new FeeCalculator();

    public ConversionService(RateRepository rates) { this.rates = rates; }

    public ConversionResult convert(String base, String quote, double amount) {
        Rate rate = rates.findLatestForPair(base, quote)
                .orElseThrow(() -> new UnknownPairException(base + "/" + quote));
        double converted = round2(amount * rate.rate());
        // Monday's TDD kata pays its first dividend: retail fee on the converted amount
        double fee = fees.feeFor(converted, false);
        return new ConversionResult(rate.pair(), amount, rate.rate(), converted, fee, round2(converted - fee));
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
