package com.example.loancollections.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class PriorityScoringTest {

    @Test
    void blockingRestrictionGatesScoreToIneligible() {
        var result = PriorityScoring.score(120, new BigDecimal("50000"), "HIGH", 0, false, Set.of("DO_NOT_CONTACT"));
        assertEquals("INELIGIBLE", result.band());
        assertEquals(new BigDecimal("0.00"), result.value());
        assertTrue(result.reasonCodes().contains("RESTRICTED_DO_NOT_CONTACT"));
        assertEquals("DPD=40.00;BALANCE=15.00;RISK=20.00;PROMISES=0.00;GATE=-75.00", result.breakdown());
    }

    @Test
    void scoreIsSumOfExplainedComponents() {
        var result = PriorityScoring.score(45, new BigDecimal("20000"), "MEDIUM", 1, false, Set.of());
        assertEquals("DPD=20.00;BALANCE=6.00;RISK=10.00;PROMISES=5.00", result.breakdown());
        assertEquals(new BigDecimal("41.00"), result.value());
        assertEquals("MEDIUM", result.band());
        assertEquals(java.util.List.of("DPD_30_PLUS", "ARREARS_PRESENT", "RISK_MEDIUM", "BROKEN_PROMISES"), result.reasonCodes());
    }

    @Test
    void hardshipCapsScoreAtFifty() {
        var result = PriorityScoring.score(120, new BigDecimal("150000"), "HIGH", 3, true, Set.of());
        assertEquals(new BigDecimal("50.00"), result.value());
        assertEquals("MEDIUM", result.band());
        assertTrue(result.breakdown().endsWith("CAP=-50.00"));
    }

    @Test
    void higherDelinquencyAndBalanceIncreaseScore() {
        var low = PriorityScoring.score(5, new BigDecimal("1000"), "LOW", 0, false, Set.of());
        var high = PriorityScoring.score(90, new BigDecimal("100000"), "HIGH", 0, false, Set.of());
        assertTrue(high.value().compareTo(low.value()) > 0);
        assertEquals("HIGH", high.band());
    }

    @Test
    void negativeInputIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PriorityScoring.score(-1, BigDecimal.ZERO, "LOW", 0, false, Set.of()));
    }
}
