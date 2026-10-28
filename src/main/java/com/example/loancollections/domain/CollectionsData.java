package com.example.loancollections.domain;

import com.example.loancollections.domain.ContactPolicy.Preference;
import com.example.loancollections.domain.LoanPosition;
import com.example.loancollections.domain.PromiseOutcome;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** In-memory read model of the collections tables, used for assessment, exception detection and evaluation. */
public class CollectionsData {
    public record CaseRow(long id, String caseRef, long loanId, String loanRef, long accountId, String status,
                          String riskGrade, BigDecimal priorityScore, String priorityBand, String scoreBreakdown,
                          String reasonCodes) {}
    public record LoanRow(long id, String loanRef, long accountId, String status, String riskGrade) {}
    public record InstallmentRow(long loanId, String ref, int number, LocalDate dueDate, BigDecimal totalDue, String status) {}
    public record AllocationRow(String ref, long loanId, int installmentNumber, BigDecimal amount, LocalDate receivedDate) {}
    public record PaymentRow(String ref, long loanId, BigDecimal amount, LocalDate receivedDate, String status) {}
    public record PromiseRow(long id, String ref, long caseId, BigDecimal amount, LocalDate promiseDate, Instant createdAt, String status) {}
    public record AttemptRow(String ref, long caseId, String channel, Instant scheduledAt, Instant attemptedAt, String outcome) {}
    public record HistoryRow(String ref, long caseId, String previousStatus, String newStatus, Instant occurredAt) {}
    public record SnapshotRow(String ref, long loanId, LocalDate asOf, int dpd, BigDecimal arrears, String riskBand) {}

    final List<CaseRow> cases;
    final Map<Long, LoanRow> loans;
    final Map<Long, List<InstallmentRow>> installments;
    final Map<Long, List<AllocationRow>> allocations;
    final Map<Long, List<PaymentRow>> payments;
    final Map<Long, List<Preference>> preferences;
    final Map<Long, List<PromiseRow>> promises;
    final Map<Long, List<AttemptRow>> attempts;
    final Map<Long, List<HistoryRow>> history;
    final Map<Long, List<SnapshotRow>> snapshots;

    public CollectionsData(List<CaseRow> cases, List<LoanRow> loans, List<InstallmentRow> installments,
                    List<AllocationRow> allocations, List<PaymentRow> payments, Map<Long, List<Preference>> preferences,
                    List<PromiseRow> promises, List<AttemptRow> attempts, List<HistoryRow> history, List<SnapshotRow> snapshots) {
        this.cases = cases;
        this.loans = loans.stream().collect(Collectors.toMap(LoanRow::id, Function.identity()));
        this.installments = group(installments, InstallmentRow::loanId);
        this.allocations = group(allocations, AllocationRow::loanId);
        this.payments = group(payments, PaymentRow::loanId);
        this.preferences = preferences;
        this.promises = group(promises, PromiseRow::caseId);
        this.attempts = group(attempts, AttemptRow::caseId);
        this.history = group(history, HistoryRow::caseId);
        this.snapshots = group(snapshots, SnapshotRow::loanId);
    }

    private static <T> Map<Long, List<T>> group(List<T> rows, Function<T, Long> key) {
        return rows.stream().collect(Collectors.groupingBy(key));
    }

    public List<CaseRow> cases() { return cases; }
    public java.util.Collection<LoanRow> loans() { return loans.values(); }
    public List<InstallmentRow> installments(long loanId) { return installments.getOrDefault(loanId, List.of()); }
    public List<AllocationRow> allocations(long loanId) { return allocations.getOrDefault(loanId, List.of()); }
    public List<Preference> preferences(long accountId) { return preferences.getOrDefault(accountId, List.of()); }
    public List<PromiseRow> promises(long caseId) { return promises.getOrDefault(caseId, List.of()); }
    public List<AttemptRow> attempts(long caseId) { return attempts.getOrDefault(caseId, List.of()); }
    public List<HistoryRow> history(long caseId) { return history.getOrDefault(caseId, List.of()); }
    public List<SnapshotRow> snapshots(long loanId) { return snapshots.getOrDefault(loanId, List.of()); }

    public List<LoanPosition.Scheduled> schedule(long loanId) {
        return installments(loanId).stream().map(i -> new LoanPosition.Scheduled(i.number(), i.dueDate(), i.totalDue())).toList();
    }

    public LoanPosition position(long loanId, LocalDate asOf) {
        return position(loanId, asOf, null);
    }

    /** Position at {@code asOf}, optionally counting only payments received on or before {@code receivedLimit}. */
    public LoanPosition position(long loanId, LocalDate asOf, LocalDate receivedLimit) {
        List<LoanPosition.Allocation> allocs = allocations(loanId).stream()
                .filter(a -> receivedLimit == null || !a.receivedDate().isAfter(receivedLimit))
                .map(a -> new LoanPosition.Allocation(a.installmentNumber(), a.amount(), a.receivedDate())).toList();
        return LoanPosition.of(schedule(loanId), allocs, asOf);
    }

    public List<PromiseOutcome.Payment> promisePayments(long loanId) {
        return payments.getOrDefault(loanId, List.of()).stream()
                .map(p -> new PromiseOutcome.Payment(p.ref(), p.amount(), p.receivedDate(), p.status())).toList();
    }
}
