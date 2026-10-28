package com.example.loancollections.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DelinquencyCalculatorTest {
    @Test
    void arrearsIncludesOnlyPastDueUnpaidAmounts() {
        var obligations = List.of(
                new DelinquencyCalculator.Obligation(LocalDate.parse("2026-10-01"), new BigDecimal("100.00"), new BigDecimal("40.00")),
                new DelinquencyCalculator.Obligation(LocalDate.parse("2026-10-27"), new BigDecimal("50.00"), new BigDecimal("0.00")),
                new DelinquencyCalculator.Obligation(LocalDate.parse("2026-10-28"), new BigDecimal("25.00"), new BigDecimal("0.00")));
        assertEquals(new BigDecimal("110.00"), DelinquencyCalculator.arrears(obligations, LocalDate.parse("2026-10-28")));
    }

    @Test
    void dpdUsesOldestOutstandingOverdueInstallment() {
        var obligations = List.of(
                new DelinquencyCalculator.Obligation(LocalDate.parse("2026-10-01"), new BigDecimal("100.00"), new BigDecimal("100.00")),
                new DelinquencyCalculator.Obligation(LocalDate.parse("2026-10-10"), new BigDecimal("50.00"), new BigDecimal("0.00")));
        assertEquals(18, DelinquencyCalculator.daysPastDue(obligations, LocalDate.parse("2026-10-28")));
    }

    @Test
    void fullyPaidPastDueObligationsHaveNoDelinquency() {
        var obligations = List.of(
                new DelinquencyCalculator.Obligation(LocalDate.parse("2026-10-01"), new BigDecimal("100.00"), new BigDecimal("100.00")));
        assertEquals(new BigDecimal("0.00"), DelinquencyCalculator.arrears(obligations, LocalDate.parse("2026-10-28")));
        assertEquals(0, DelinquencyCalculator.daysPastDue(obligations, LocalDate.parse("2026-10-28")));
    }
}
