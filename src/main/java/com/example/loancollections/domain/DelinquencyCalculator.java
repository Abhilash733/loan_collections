package com.example.loancollections.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;

public final class DelinquencyCalculator {
    private DelinquencyCalculator() {}

    public record Obligation(LocalDate dueDate, BigDecimal amountDue, BigDecimal netValidAllocations) {
        public Obligation {
            if (dueDate == null || amountDue == null || netValidAllocations == null) {
                throw new IllegalArgumentException("Obligation fields must not be null");
            }
            if (amountDue.signum() < 0 || netValidAllocations.signum() < 0) {
                throw new IllegalArgumentException("Amounts must not be negative");
            }
        }
        public BigDecimal remaining() {
            return amountDue.subtract(netValidAllocations).max(BigDecimal.ZERO);
        }
    }

    public static BigDecimal arrears(Collection<Obligation> obligations, LocalDate asOfDate) {
        if (asOfDate == null) throw new IllegalArgumentException("asOfDate is required");
        return obligations.stream()
                .filter(o -> o.dueDate().isBefore(asOfDate))
                .map(Obligation::remaining)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public static int daysPastDue(Collection<Obligation> obligations, LocalDate asOfDate) {
        if (asOfDate == null) throw new IllegalArgumentException("asOfDate is required");
        return obligations.stream()
                .filter(o -> o.dueDate().isBefore(asOfDate) && o.remaining().signum() > 0)
                .mapToInt(o -> Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(o.dueDate(), asOfDate)))
                .max().orElse(0);
    }
}
