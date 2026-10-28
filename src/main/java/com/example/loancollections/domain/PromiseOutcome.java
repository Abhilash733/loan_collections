package com.example.loancollections.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reconciles a promise-to-pay against subsequent payments. Qualifying payments are SUCCEEDED and received
 * between the promise creation date and the promise date (inclusive), counted up to the promised amount.
 */
public record PromiseOutcome(String status, List<Link> links, BigDecimal qualified, String reason) {
    public record Link(String paymentRef, BigDecimal amount) {}
    public record Payment(String ref, BigDecimal amount, LocalDate receivedDate, String status) {}

    public static PromiseOutcome evaluate(BigDecimal promised, LocalDate createdDate, LocalDate promiseDate,
                                          List<Payment> payments, LocalDate asOf) {
        LocalDate end = promiseDate.isBefore(asOf) ? promiseDate : asOf;
        List<Payment> qualifying = payments.stream()
                .filter(p -> "SUCCEEDED".equals(p.status()) && !p.receivedDate().isBefore(createdDate) && !p.receivedDate().isAfter(end))
                .sorted(Comparator.comparing(Payment::receivedDate).thenComparing(Payment::ref))
                .toList();
        List<Link> links = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Payment p : qualifying) {
            if (total.compareTo(promised) >= 0) break;
            BigDecimal part = p.amount().min(promised.subtract(total));
            links.add(new Link(p.ref(), part));
            total = total.add(part);
        }
        String status;
        if (total.compareTo(promised) >= 0) status = "FULFILLED";
        else if (!asOf.isAfter(promiseDate)) status = "PENDING";
        else if (total.signum() > 0) status = "PARTIALLY_FULFILLED";
        else status = "MISSED";
        return new PromiseOutcome(status, List.copyOf(links), total,
                "Qualifying payments " + total.toPlainString() + " of " + promised.toPlainString() + " by " + promiseDate);
    }

    public boolean broken() {
        return "MISSED".equals(status) || "PARTIALLY_FULFILLED".equals(status);
    }
}
