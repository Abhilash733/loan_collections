package com.example.loancollections.model.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "collection_cases", indexes = @Index(name = "idx_cases_loan_status", columnList = "loan_id,status"))
public class CollectionCase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_ref", nullable = false, unique = true, length = 50)
    private String caseRef;

    @Column(name = "loan_id", nullable = false)
    private Long loanId;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "priority_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal priorityScore;

    @Column(name = "priority_band", nullable = false, length = 16)
    private String priorityBand;

    @Column(name = "score_breakdown", nullable = false, length = 200)
    private String scoreBreakdown;

    @Column(name = "reason_codes", nullable = false, length = 500)
    private String reasonCodes;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected CollectionCase() {}

    public Long getId() { return id; }
    public String getCaseRef() { return caseRef; }
    public Long getLoanId() { return loanId; }
    public String getStatus() { return status; }
    public BigDecimal getPriorityScore() { return priorityScore; }
    public String getPriorityBand() { return priorityBand; }
    public String getScoreBreakdown() { return scoreBreakdown; }
    public String getReasonCodes() { return reasonCodes; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getClosedAt() { return closedAt; }
}
