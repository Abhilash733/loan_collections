package com.example.loancollections.model.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Explainable view of one case at a date: balances, gates, score breakdown and the decided next action. */
public record CaseAssessment(String caseRef, String loanRef, LocalDate asOf, String caseStatus, int daysPastDue,
                             BigDecimal arrears, boolean paidOff, String riskBand, BigDecimal priorityScore,
                             String priorityBand, String scoreBreakdown, List<String> reasonCodes,
                             Set<String> blockingRestrictions, boolean hardship, boolean pendingPromise,
                             int brokenPromises, String nextAction, String expectedBand, String contactChannel,
                             Instant nextContactSlot) {
    public boolean actionable() {
        return "SCHEDULE_CONTACT".equals(nextAction) || "CONTACT_HARDSHIP_SUPPORT".equals(nextAction);
    }
}
