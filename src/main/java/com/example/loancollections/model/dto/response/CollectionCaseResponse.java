package com.example.loancollections.model.dto.response;

import com.example.loancollections.model.entity.CollectionCase;
import java.math.BigDecimal;
import java.time.Instant;

public record CollectionCaseResponse(String caseRef, Long loanId, String status,
                                     BigDecimal priorityScore, String priorityBand,
                                     String scoreBreakdown, String reasonCodes,
                                     Instant openedAt, Instant closedAt) {
    public static CollectionCaseResponse from(CollectionCase c) {
        return new CollectionCaseResponse(c.getCaseRef(), c.getLoanId(), c.getStatus(),
                c.getPriorityScore(), c.getPriorityBand(), c.getScoreBreakdown(), c.getReasonCodes(),
                c.getOpenedAt(), c.getClosedAt());
    }
}
