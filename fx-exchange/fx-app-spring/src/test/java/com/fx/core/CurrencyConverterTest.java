package com.fx.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the rate-application and guard behaviour of CurrencyConverter. */
class CurrencyConverterTest {

    private CurrencyConverter converter;

    @BeforeEach void setUp() { converter = new CurrencyConverter(1.0875); }

    @Test @DisplayName("converts at configured rate")
    void convertsAtRate() { assertEquals(108.75, converter.convert(100), 1e-9); }

    @Test void overrideRateWins() { assertEquals(109.0, converter.convert(100, 1.09), 1e-9); }

    @Test void arrayOverloadConvertsEach() {
        assertArrayEquals(new double[]{10.875, 21.75}, converter.convert(new double[]{10, 20}), 1e-9);
    }

    @Test void withdrawBeyondBalanceThrowsWithContext() {
        Account a = new Account("T", Currency.EUR, 100);
        InsufficientFundsException ex =
            assertThrows(InsufficientFundsException.class, () -> a.withdraw(250));
        assertAll(
            () -> assertEquals(250, ex.getRequested(), 1e-9),
            () -> assertEquals(100, ex.getAvailable(), 1e-9),
            () -> assertTrue(ex.getMessage().contains("250.0")));
    }

    @ParameterizedTest(name = "{0} * {1} = {2}")
    @CsvSource({"100,1.10,110.0", "0.01,1.10,0.011", "50,2.00,100.0"})
    void conversionTable(double amount, double rate, double expected) {
        assertEquals(expected, new CurrencyConverter(rate).convert(amount), 1e-9);
    }

    @Test @Disabled("demo of @Disabled — discuss when this is legitimate")
    void flakyNetworkTest() {}
}
