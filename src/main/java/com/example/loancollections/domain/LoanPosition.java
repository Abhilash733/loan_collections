package com.example.loancollections.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Balances of one loan at the end of {@code asOf}: installments due before asOf are in arrears,
 * and only ACTIVE allocations of SUCCEEDED payments received on or before asOf reduce an obligation.
 */
public record LoanPosition(BigDecimal arrears, int daysPastDue, Map<Integer, BigDecimal> remaining,
                           Map<Integer, BigDecimal> paid, boolean paidOff) {

    public record Scheduled(int number, LocalDate dueDate, BigDecimal totalDue) {}
    public record Allocation(int installmentNumber, BigDecimal amount, LocalDate receivedDate) {}

    public static LoanPosition of(List<Scheduled> schedule, Collection<Allocation> allocations, LocalDate asOf) {
        Map<Integer, BigDecimal> paid = new TreeMap<>();
        schedule.forEach(s -> paid.put(s.number(), BigDecimal.ZERO));
        for (Allocation a : allocations) {
            if (!a.receivedDate().isAfter(asOf)) paid.merge(a.installmentNumber(), a.amount(), BigDecimal::add);
        }
        Map<Integer, BigDecimal> remaining = new TreeMap<>();
        List<DelinquencyCalculator.Obligation> obligations = new ArrayList<>();
        for (Scheduled s : schedule) {
            BigDecimal p = paid.get(s.number());
            remaining.put(s.number(), s.totalDue().subtract(p).max(BigDecimal.ZERO));
            obligations.add(new DelinquencyCalculator.Obligation(s.dueDate(), s.totalDue(), p));
        }
        boolean paidOff = remaining.values().stream().allMatch(v -> v.signum() == 0);
        return new LoanPosition(DelinquencyCalculator.arrears(obligations, asOf),
                DelinquencyCalculator.daysPastDue(obligations, asOf), remaining, paid, paidOff);
    }
}
