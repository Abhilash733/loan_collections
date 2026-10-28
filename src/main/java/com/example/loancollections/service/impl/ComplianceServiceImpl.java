package com.example.loancollections.service.impl;

import com.example.loancollections.domain.CollectionsData;
import com.example.loancollections.domain.CollectionsData.*;
import com.example.loancollections.domain.CollectionsRules;
import com.example.loancollections.domain.ContactPolicy;
import com.example.loancollections.domain.ContactPolicy.Preference;
import com.example.loancollections.domain.LoanPosition;
import com.example.loancollections.model.dto.response.RuleViolation;
import com.example.loancollections.repository.jdbc.CollectionsDataRepository;
import com.example.loancollections.service.ComplianceService;
import com.example.loancollections.util.DateUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ComplianceServiceImpl implements ComplianceService {
    private static final Map<String, String> SEVERITY = Map.of(
            "PAID_ACCOUNT_IN_COLLECTION", "HIGH", "CONTACT_OUTSIDE_WINDOW", "HIGH", "CONTACT_DURING_RESTRICTION", "CRITICAL",
            "WRONG_INSTALLMENT_ALLOCATION", "CRITICAL", "ARREARS_RECONCILIATION", "HIGH", "PROMISE_EXCEEDS_ARREARS", "MEDIUM",
            "DISPUTED_TREATED_AS_CONFIRMED", "CRITICAL", "INVALID_CASE_TRANSITION", "MEDIUM");
    private static final Set<String> CONFIRMED_STATUSES = Set.of("OPEN", "IN_PROGRESS", "AWAITING_PROMISE");

    private final CollectionsDataRepository repository;

    public ComplianceServiceImpl(CollectionsDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<RuleViolation> detect(LocalDate asOf) {
        return detect(repository.load(), asOf);
    }

    @Override
    public List<RuleViolation> detect(CollectionsData data, LocalDate asOf) {
        List<RuleViolation> out = new ArrayList<>();
        for (CaseRow c : data.cases()) checkCase(data, c, asOf, out);
        for (LoanRow l : data.loans()) checkLoan(data, l, asOf, out);
        return out;
    }

    private void checkCase(CollectionsData data, CaseRow c, LocalDate asOf, List<RuleViolation> out) {
        List<Preference> prefs = data.preferences(c.accountId());
        if (CollectionsRules.ACTIVE_CASE_STATUSES.contains(c.status()) && data.position(c.loanId(), asOf).paidOff())
            add(out, "PAID_ACCOUNT_IN_COLLECTION", "COLLECTION_CASE", c.caseRef(), c.loanRef(),
                    "Case is " + c.status() + " but the loan has no outstanding obligation");
        if (CONFIRMED_STATUSES.contains(c.status()) && ContactPolicy.activeRestrictions(prefs, asOf).stream()
                .anyMatch(p -> "DISPUTE".equals(p.restrictionType())))
            add(out, "DISPUTED_TREATED_AS_CONFIRMED", "COLLECTION_CASE", c.caseRef(), c.loanRef(),
                    "Case is " + c.status() + " while a dispute restriction is open");

        for (AttemptRow a : data.attempts(c.id())) {
            if (a.attemptedAt() == null) continue;
            Optional<Preference> base = ContactPolicy.basePreference(prefs, DateUtils.utcDate(a.attemptedAt()));
            if (base.isEmpty()) {
                add(out, "CONTACT_OUTSIDE_WINDOW", "CONTACT_ATTEMPT", a.ref(), c.loanRef(), "No contact preference in effect");
                continue;
            }
            ZoneId zone = ZoneId.of(base.get().timezoneId());
            CollectionsRules.contactWindowViolation(a.attemptedAt(), zone, base.get().start(), base.get().end())
                    .ifPresent(v -> add(out, "CONTACT_OUTSIDE_WINDOW", "CONTACT_ATTEMPT", a.ref(), c.loanRef(),
                            v + " at " + a.attemptedAt().atZone(zone).toLocalTime() + " local (" + base.get().start() + "-" + base.get().end() + ")"));
            LocalDate localDay = a.attemptedAt().atZone(zone).toLocalDate();
            String breached = ContactPolicy.activeRestrictions(prefs, localDay).stream()
                    .filter(p -> CollectionsRules.BLOCKING_RESTRICTIONS.contains(p.restrictionType())
                            || ("HARDSHIP".equals(p.restrictionType()) && CollectionsRules.HARDSHIP_BLOCKED_CHANNELS.contains(a.channel())))
                    .map(p -> p.restrictionType() + " (" + p.ref() + ")").collect(Collectors.joining(", "));
            if (!breached.isEmpty())
                add(out, "CONTACT_DURING_RESTRICTION", "CONTACT_ATTEMPT", a.ref(), c.loanRef(), a.channel() + " contact during " + breached);
        }

        for (PromiseRow p : data.promises(c.id())) {
            LocalDate created = DateUtils.utcDate(p.createdAt());
            BigDecimal arrears = data.position(c.loanId(), created).arrears();
            if (p.amount().compareTo(arrears) > 0)
                add(out, "PROMISE_EXCEEDS_ARREARS", "PROMISE_TO_PAY", p.ref(), c.loanRef(),
                        "Promised " + p.amount() + " while arrears on " + created + " were " + arrears);
        }

        for (HistoryRow h : data.history(c.id())) {
            if (!CollectionsRules.isAllowedTransition(h.previousStatus(), h.newStatus()))
                add(out, "INVALID_CASE_TRANSITION", "CASE_STATUS_HISTORY", h.ref(), c.loanRef(),
                        (h.previousStatus() == null ? "(none)" : h.previousStatus()) + " -> " + h.newStatus());
        }
    }

    private void checkLoan(CollectionsData data, LoanRow l, LocalDate asOf, List<RuleViolation> out) {
        LoanPosition pos = data.position(l.id(), asOf);
        List<String> issues = new ArrayList<>();
        if (!CollectionsRules.loanStatus(pos).equals(l.status()))
            issues.add("loan status " + l.status() + " != derived " + CollectionsRules.loanStatus(pos));
        for (InstallmentRow i : data.installments(l.id())) {
            BigDecimal paid = pos.paid().get(i.number());
            String derived = CollectionsRules.installmentStatus(pos.remaining().get(i.number()), paid, i.dueDate(), asOf);
            if (paid.compareTo(i.totalDue()) > 0) issues.add(i.ref() + " over-allocated by " + paid.subtract(i.totalDue()));
            if (!derived.equals(i.status())) issues.add(i.ref() + " status " + i.status() + " != derived " + derived);
        }
        for (SnapshotRow s : data.snapshots(l.id())) {
            if (s.asOf().isAfter(asOf)) continue;
            LoanPosition p = data.position(l.id(), s.asOf());
            String band = CollectionsRules.riskBand(p.daysPastDue(), l.riskGrade());
            if (p.daysPastDue() != s.dpd() || p.arrears().compareTo(s.arrears()) != 0 || !band.equals(s.riskBand()))
                issues.add(s.ref() + " arrears " + s.arrears() + " != derived " + p.arrears());
        }
        if (!issues.isEmpty())
            add(out, "ARREARS_RECONCILIATION", "LOAN", l.loanRef(), l.loanRef(), String.join("; ", issues.subList(0, Math.min(3, issues.size()))));

        Map<Integer, LocalDate> dues = data.installments(l.id()).stream()
                .collect(Collectors.toMap(InstallmentRow::number, InstallmentRow::dueDate));
        for (AllocationRow a : data.allocations(l.id())) {
            LocalDate due = dues.get(a.installmentNumber());
            if (due == null || !due.isAfter(a.receivedDate())) continue;
            LoanPosition before = data.position(l.id(), asOf, a.receivedDate());
            boolean skipped = before.remaining().entrySet().stream()
                    .anyMatch(e -> e.getKey() < a.installmentNumber() && e.getValue().signum() > 0);
            if (skipped)
                add(out, "WRONG_INSTALLMENT_ALLOCATION", "PAYMENT_ALLOCATION", a.ref(), l.loanRef(),
                        "Applied to not-yet-due installment " + a.installmentNumber() + " while an earlier installment was outstanding");
        }
    }

    private static void add(List<RuleViolation> out, String rule, String entity, String ref, String loanRef, String detail) {
        out.add(new RuleViolation(rule, SEVERITY.get(rule), entity, ref, loanRef, detail));
    }
}
