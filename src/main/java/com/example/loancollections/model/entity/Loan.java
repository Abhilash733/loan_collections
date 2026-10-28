package com.example.loancollections.model.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;

@Entity
@Table(name = "loans", indexes = @Index(name = "idx_loans_account_id", columnList = "account_id"))
public class Loan {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loan_ref", nullable = false, unique = true, length = 40)
    private String loanRef;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "principal_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal principalAmount;

    @Column(name = "annual_interest_rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal annualInterestRate;

    @Column(name = "term_months", nullable = false)
    private Integer termMonths;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Loan() {}

    public Loan(String loanRef, Long accountId, BigDecimal principalAmount,
                BigDecimal annualInterestRate, Integer termMonths, LocalDate startDate, String status) {
        this.loanRef = loanRef;
        this.accountId = accountId;
        this.principalAmount = principalAmount;
        this.annualInterestRate = annualInterestRate;
        this.termMonths = termMonths;
        this.startDate = startDate;
        this.status = status;
    }

    public Long getId() { return id; }
    public String getLoanRef() { return loanRef; }
    public Long getAccountId() { return accountId; }
    public BigDecimal getPrincipalAmount() { return principalAmount; }
    public BigDecimal getAnnualInterestRate() { return annualInterestRate; }
    public Integer getTermMonths() { return termMonths; }
    public LocalDate getStartDate() { return startDate; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    @PrePersist
    void prePersist() { if (createdAt == null) createdAt = Instant.now(); }
}
