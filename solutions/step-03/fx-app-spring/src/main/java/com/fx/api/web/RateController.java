package com.fx.api.web;

import com.fx.api.model.ConversionRequest;
import com.fx.api.model.ConversionResult;
import com.fx.api.model.Rate;
import com.fx.api.repo.RateRepository;
import com.fx.api.service.ConversionService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Resource-oriented REST endpoints over rates + conversions. */
@RestController
@RequestMapping("/api")
public class RateController {

    private static final Logger log = LoggerFactory.getLogger(RateController.class);

    private final RateRepository rates;
    private final ConversionService conversions;

    public RateController(RateRepository rates, ConversionService conversions) {
        this.rates = rates;
        this.conversions = conversions;
    }

    @GetMapping("/rates")
    public List<Rate> latestRates() {
        log.info("GET /api/rates");
        return rates.findLatest();
    }

    @GetMapping("/rates/{base}/{quote}")
    public Rate latestForPair(@PathVariable String base, @PathVariable String quote) {
        return rates.findLatestForPair(base.toUpperCase(), quote.toUpperCase())
                .orElseThrow(() -> new com.fx.api.service.UnknownPairException(base + "/" + quote));
    }

    @PostMapping("/conversions")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversionResult convert(@Valid @RequestBody ConversionRequest req) {
        log.info("POST /api/conversions {} {}->{}", req.amount(), req.base(), req.quote());
        return conversions.convert(req.base(), req.quote(), req.amount());
    }
}
