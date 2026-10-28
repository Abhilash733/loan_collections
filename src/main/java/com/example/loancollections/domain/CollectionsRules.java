package com.example.loancollections.domain;

import com.example.loancollections.domain.LoanPosition;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Business rules shared by the work queue, contact policy and exception detection (see docs/business-rules.md). */
public final class CollectionsRules {
    private CollectionsRules() {}

    public static final int CASE_OPEN_DPD = 15;
    public static final LocalTime GLOBAL_WINDOW_START = LocalTime.of(8, 0);
    public static final LocalTime GLOBAL_WINDOW_END = LocalTime.of(20, 0);
    public static final Set<String> ACTIVE_CASE_STATUSES = Set.of("OPEN", "IN_PROGRESS", "AWAITING_PROMISE", "DISPUTED");
    public static final Set<String> BLOCKING_RESTRICTIONS = Set.of("DO_NOT_CONTACT", "LEGAL_HOLD", "DISPUTE");
    public static final Set<String> HARDSHIP_BLOCKED_CHANNELS = Set.of("PHONE", "SMS");
    public static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "", Set.of("OPEN"),
            "OPEN", Set.of("IN_PROGRESS", "DISPUTED", "RESOLVED"),
            "IN_PROGRESS", Set.of("AWAITING_PROMISE", "DISPUTED", "RESOLVED"),
            "AWAITING_PROMISE", Set.of("IN_PROGRESS", "DISPUTED", "RESOLVED"),
            "DISPUTED", Set.of("IN_PROGRESS", "RESOLVED", "CLOSED"),
            "RESOLVED", Set.of("IN_PROGRESS", "CLOSED"),
            "CLOSED", Set.of());

    public static boolean isAllowedTransition(String from, String to) {
        return ALLOWED_TRANSITIONS.getOrDefault(from == null ? "" : from, Set.of()).contains(to);
    }

    public static String riskBand(int dpd, String riskGrade) {
        if (dpd >= 60 || ("D".equals(riskGrade) && dpd >= 30)) return "HIGH";
        if (dpd >= 30 || (("C".equals(riskGrade) || "D".equals(riskGrade)) && dpd > 0)) return "MEDIUM";
        return "LOW";
    }

    public static String installmentStatus(BigDecimal remaining, BigDecimal paid, LocalDate due, LocalDate asOf) {
        if (remaining.signum() == 0) return "PAID";
        if (paid.signum() > 0) return "PARTIALLY_PAID";
        return due.isBefore(asOf) ? "OVERDUE" : "SCHEDULED";
    }

    public static String loanStatus(LoanPosition p) {
        if (p.paidOff()) return "PAID_OFF";
        return p.daysPastDue() >= 90 ? "DEFAULTED" : "ACTIVE";
    }

    public static boolean restrictionActive(LocalDate from, LocalDate to, LocalDate day) {
        return !from.isAfter(day) && (to == null || !day.isAfter(to));
    }

    /** Reason a contact at {@code at} is not permissible in the customer's local time, if any. */
    public static Optional<String> contactWindowViolation(Instant at, ZoneId zone, LocalTime start, LocalTime end) {
        ZonedDateTime local = at.atZone(zone);
        if (local.getDayOfWeek() == DayOfWeek.SUNDAY) return Optional.of("SUNDAY");
        LocalTime t = local.toLocalTime();
        if (t.isBefore(GLOBAL_WINDOW_START) || !t.isBefore(GLOBAL_WINDOW_END)) return Optional.of("OUTSIDE_GLOBAL_WINDOW");
        if (t.isBefore(start) || !t.isBefore(end)) return Optional.of("OUTSIDE_CUSTOMER_WINDOW");
        return Optional.empty();
    }

    public static String nextAction(String caseStatus, LoanPosition p, Set<String> blocking,
                                    boolean pendingPromise, boolean hardship) {
        if (p.paidOff()) return "CLOSED".equals(caseStatus) ? "NO_ACTION" : "CLOSE_CASE";
        if ("RESOLVED".equals(caseStatus) || "CLOSED".equals(caseStatus)) return p.arrears().signum() > 0 ? "MONITOR" : "NO_ACTION";
        if (p.arrears().signum() == 0) return "RESOLVE_CASE";
        if (blocking.contains("DISPUTE")) return "HOLD_DISPUTE";
        if (!blocking.isEmpty()) return "NO_CONTACT_RESTRICTED";
        if (pendingPromise) return "AWAIT_PROMISE";
        if (hardship) return "CONTACT_HARDSHIP_SUPPORT";
        return "SCHEDULE_CONTACT";
    }

    public static String expectedBand(String caseStatus, String nextAction, String priorityBand) {
        return "CLOSE_CASE".equals(nextAction) || "RESOLVED".equals(caseStatus) || "CLOSED".equals(caseStatus)
                ? "NONE" : priorityBand;
    }
}
