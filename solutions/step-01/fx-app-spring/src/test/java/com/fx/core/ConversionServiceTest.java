package com.fx.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Unit tests for ConversionService with the RateFeed collaborator mocked out. */
@ExtendWith(MockitoExtension.class)
class ConversionServiceTest {

    @Mock RateFeed feed;

    @Test void usesFeedRate() {
        when(feed.rateFor("EUR/USD")).thenReturn(1.10);
        assertEquals(110.0, new ConversionService(feed).convert("EUR/USD", 100), 1e-9);
        verify(feed).rateFor("EUR/USD");          // interaction check
    }

    @Test void spreadIsAppliedInBasisPoints() {
        when(feed.rateFor("EUR/USD")).thenReturn(1.00);
        // 50 bps = 0.5% -> rate 1.005
        assertEquals(100.5, new ConversionService(feed).convertWithSpread("EUR/USD", 100, 50), 1e-9);
    }

    @Test void rejectsNonPositiveAmountWithoutCallingFeed() {
        assertThrows(IllegalArgumentException.class,
            () -> new ConversionService(feed).convert("EUR/USD", 0));
        verifyNoInteractions(feed);               // guard clause runs FIRST
    }

    @Test void unknownPairBubblesUp() {
        when(feed.rateFor("XXX/YYY")).thenThrow(new IllegalArgumentException("unknown pair"));
        assertThrows(IllegalArgumentException.class,
            () -> new ConversionService(feed).convert("XXX/YYY", 10));
    }
}
