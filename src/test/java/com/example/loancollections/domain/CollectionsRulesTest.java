package com.example.loancollections.domain;

import com.example.loancollections.domain.LoanPosition;
import com.example.loancollections.domain.LoanPosition.Allocation;
import com.example.loancollections.domain.LoanPosition.Scheduled;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CollectionsRulesTest {
    private static final LocalDate AS_OF = LocalDate.parse("2026-10-28");
    private static final List<Scheduled> SCHEDULE = List.of(
            new Scheduled(1, LocalDate.parse("2026-08-01"), new BigDecimal("100.00")),
            new Scheduled(2, LocalDate.parse("2026-09-01"), new BigDecimal("100.00")),
            new Scheduled(3, LocalDate.parse("2026-11-01"), new BigDecimal("100.00")));

    @Test
    void positionCountsOnlyPaymentsReceivedByAsOfDate() {
        var pos = LoanPosition.of(SCHEDULE, List.of(new Allocation(1, new BigDecimal("100.00"), LocalDate.parse("2026-08-02")),
                new Allocation(2, new BigDecimal("40.00"), LocalDate.parse("2026-10-29"))), AS_OF);
        assertEquals(new BigDecimal("100.00"), pos.arrears());
        assertEquals(57, pos.daysPastDue());
        assertEquals("OVERDUE", CollectionsRules.installmentStatus(pos.remaining().get(2), pos.paid().get(2), LocalDate.parse("2026-09-01"), AS_OF));
        assertEquals("SCHEDULED", CollectionsRules.installmentStatus(pos.remaining().get(3), pos.paid().get(3), LocalDate.parse("2026-11-01"), AS_OF));
        assertFalse(pos.paidOff());
    }

    @Test
    void nextActionHonoursClosureDisputesRestrictionsAndPromises() {
        var paidOff = LoanPosition.of(SCHEDULE, List.of(new Allocation(1, new BigDecimal("100.00"), AS_OF),
                new Allocation(2, new BigDecimal("100.00"), AS_OF), new Allocation(3, new BigDecimal("100.00"), AS_OF)), AS_OF);
        var owing = LoanPosition.of(SCHEDULE, List.of(), AS_OF);
        assertEquals("CLOSE_CASE", CollectionsRules.nextAction("IN_PROGRESS", paidOff, Set.of(), false, false));
        assertEquals("HOLD_DISPUTE", CollectionsRules.nextAction("IN_PROGRESS", owing, Set.of("DISPUTE"), false, false));
        assertEquals("NO_CONTACT_RESTRICTED", CollectionsRules.nextAction("OPEN", owing, Set.of("LEGAL_HOLD"), false, false));
        assertEquals("AWAIT_PROMISE", CollectionsRules.nextAction("AWAITING_PROMISE", owing, Set.of(), true, false));
        assertEquals("CONTACT_HARDSHIP_SUPPORT", CollectionsRules.nextAction("IN_PROGRESS", owing, Set.of(), false, true));
        assertEquals("SCHEDULE_CONTACT", CollectionsRules.nextAction("IN_PROGRESS", owing, Set.of(), false, false));
    }

    @Test
    void closedCaseCannotBeReopened() {
        assertTrue(CollectionsRules.isAllowedTransition(null, "OPEN"));
        assertTrue(CollectionsRules.isAllowedTransition("DISPUTED", "IN_PROGRESS"));
        assertFalse(CollectionsRules.isAllowedTransition("CLOSED", "IN_PROGRESS"));
        assertFalse(CollectionsRules.isAllowedTransition("OPEN", "AWAITING_PROMISE"));
    }

    @Test
    void riskBandUsesDpdAndGrade() {
        assertEquals("HIGH", CollectionsRules.riskBand(60, "A"));
        assertEquals("HIGH", CollectionsRules.riskBand(30, "D"));
        assertEquals("MEDIUM", CollectionsRules.riskBand(5, "C"));
        assertEquals("LOW", CollectionsRules.riskBand(5, "A"));
    }
}
