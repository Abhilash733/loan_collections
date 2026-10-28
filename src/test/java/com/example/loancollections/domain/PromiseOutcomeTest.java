package com.example.loancollections.domain;

import com.example.loancollections.domain.PromiseOutcome.Payment;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PromiseOutcomeTest {
    private static final LocalDate CREATED = LocalDate.parse("2026-10-01");
    private static final LocalDate DUE = LocalDate.parse("2026-10-10");

    private static Payment pay(String ref, String amount, String date, String status) {
        return new Payment(ref, new BigDecimal(amount), LocalDate.parse(date), status);
    }

    @Test
    void fulfilledWhenQualifyingPaymentsReachPromise() {
        var o = PromiseOutcome.evaluate(new BigDecimal("1000.00"), CREATED, DUE,
                List.of(pay("PMT-2026-000001", "600.00", "2026-10-03", "SUCCEEDED"),
                        pay("PMT-2026-000002", "700.00", "2026-10-09", "SUCCEEDED")), LocalDate.parse("2026-10-20"));
        assertEquals("FULFILLED", o.status());
        assertEquals(new BigDecimal("400.00"), o.links().get(1).amount());
    }

    @Test
    void failedPaymentsAndLatePaymentsDoNotQualify() {
        var o = PromiseOutcome.evaluate(new BigDecimal("1000.00"), CREATED, DUE,
                List.of(pay("PMT-2026-000001", "1000.00", "2026-10-05", "FAILED"),
                        pay("PMT-2026-000002", "1000.00", "2026-10-11", "SUCCEEDED")), LocalDate.parse("2026-10-20"));
        assertEquals("MISSED", o.status());
        assertTrue(o.links().isEmpty());
    }

    @Test
    void partialAfterDeadlineAndPendingBefore() {
        var payments = List.of(pay("PMT-2026-000001", "250.00", "2026-10-04", "SUCCEEDED"));
        assertEquals("PENDING", PromiseOutcome.evaluate(new BigDecimal("1000.00"), CREATED, DUE, payments, DUE).status());
        var after = PromiseOutcome.evaluate(new BigDecimal("1000.00"), CREATED, DUE, payments, DUE.plusDays(1));
        assertEquals("PARTIALLY_FULFILLED", after.status());
        assertTrue(after.broken());
    }
}
