package com.example.loancollections.service.impl;

import com.example.loancollections.domain.CollectionsData;
import com.example.loancollections.domain.CollectionsData.CaseRow;
import com.example.loancollections.domain.CollectionsRules;
import com.example.loancollections.domain.ContactPolicy;
import com.example.loancollections.domain.PromiseOutcome;
import com.example.loancollections.exception.BusinessException;
import com.example.loancollections.exception.ResourceNotFoundException;
import com.example.loancollections.model.dto.response.PromiseResult;
import com.example.loancollections.repository.jdbc.CollectionsDataRepository;
import com.example.loancollections.repository.jdbc.ContactAttemptRepository;
import com.example.loancollections.repository.jdbc.PromiseRepository;
import com.example.loancollections.service.AuditService;
import com.example.loancollections.service.CaseLifecycleService;
import com.example.loancollections.service.PromiseService;
import com.example.loancollections.util.DateUtils;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PromiseServiceImpl implements PromiseService {
    private static final Logger log = LoggerFactory.getLogger(PromiseServiceImpl.class);
    private static final int MAX_PROMISE_DAYS = 30;
    private static final Set<String> PROMISABLE_STATUSES = Set.of("OPEN", "IN_PROGRESS");
    private static final Set<String> LINKABLE_CONTACT_OUTCOMES = Set.of("SCHEDULED", "CONTACTED");

    private final CollectionsDataRepository collectionsData;
    private final PromiseRepository promises;
    private final ContactAttemptRepository attempts;
    private final CaseLifecycleService lifecycle;
    private final AuditService audit;

    public PromiseServiceImpl(CollectionsDataRepository collectionsData, PromiseRepository promises,
                              ContactAttemptRepository attempts, CaseLifecycleService lifecycle, AuditService audit) {
        this.collectionsData = collectionsData;
        this.promises = promises;
        this.attempts = attempts;
        this.lifecycle = lifecycle;
        this.audit = audit;
    }

    @Override
    @Transactional
    public PromiseResult create(String caseRef, BigDecimal amount, LocalDate promiseDate, String contactRef, String actor) {
        CaseRow c = collectionsData.findCase(caseRef)
                .orElseThrow(() -> new ResourceNotFoundException("Collection case not found: " + caseRef));
        if (!PROMISABLE_STATUSES.contains(c.status()))
            throw new BusinessException("Case " + caseRef + " is " + c.status() + "; a promise can only be taken on OPEN or IN_PROGRESS cases");
        Instant now = Instant.now();
        LocalDate today = DateUtils.utcDate(now);
        LocalDateTime nowUtc = DateUtils.toUtcDateTime(now);
        CollectionsData data = collectionsData.loadForCase(c);
        ContactPolicy.activeRestrictions(data.preferences(c.accountId()), today).stream()
                .filter(p -> CollectionsRules.BLOCKING_RESTRICTIONS.contains(p.restrictionType())).findFirst()
                .ifPresent(p -> { throw new BusinessException("Account is restricted (" + p.restrictionType() + "); no promise may be solicited"); });
        BigDecimal arrears = data.position(c.loanId(), today).arrears();
        if (amount == null || amount.signum() <= 0) throw new BusinessException("Promised amount must be positive");
        if (amount.compareTo(arrears) > 0)
            throw new BusinessException("PROMISE_EXCEEDS_ARREARS: promised " + amount + " exceeds current arrears " + arrears);
        if (promiseDate.isBefore(today) || promiseDate.isAfter(today.plusDays(MAX_PROMISE_DAYS)))
            throw new BusinessException("Promise date must be within " + MAX_PROMISE_DAYS + " days from today");

        Long attemptId = null;
        if (contactRef != null && !contactRef.isBlank()) {
            ContactAttemptRepository.Attempt attempt = attempts.findByRef(contactRef)
                    .filter(a -> a.caseId() == c.id())
                    .orElseThrow(() -> new BusinessException("Contact " + contactRef + " does not belong to case " + caseRef));
            if (!LINKABLE_CONTACT_OUTCOMES.contains(attempt.outcome()))
                throw new BusinessException("Contact " + contactRef + " is " + attempt.outcome());
            attemptId = attempt.id();
            attempts.markPromiseMade(attemptId, nowUtc, actor);
        }
        String ref = "PTP-%s-%03d".formatted(caseRef, promises.nextSequence(c.id()));
        promises.insert(ref, c.id(), attemptId, amount, promiseDate, nowUtc);
        if ("OPEN".equals(c.status())) lifecycle.transition(c.id(), caseRef, "OPEN", "IN_PROGRESS", "First contact attempt", actor);
        lifecycle.transition(c.id(), caseRef, "IN_PROGRESS", "AWAITING_PROMISE", "Promise " + ref + " recorded", actor);
        audit.record("PROMISE_TO_PAY", ref, "PROMISE_CREATED", actor, "amount=" + amount + " arrears=" + arrears);
        return new PromiseResult(ref, amount, promiseDate, arrears);
    }

    @Override
    @Transactional
    public Map<String, Integer> reconcile(LocalDate asOf, String actor) {
        CollectionsData data = collectionsData.load();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CaseRow c : data.cases()) {
            String status = c.status();
            for (CollectionsData.PromiseRow p : data.promises(c.id())) {
                if (!"PENDING".equals(p.status())) continue;
                PromiseOutcome outcome = PromiseOutcome.evaluate(p.amount(), DateUtils.utcDate(p.createdAt()),
                        p.promiseDate(), data.promisePayments(c.loanId()), asOf);
                if ("PENDING".equals(outcome.status())) continue;
                promises.updateOutcome(p.id(), outcome.status(), outcome.reason());
                outcome.links().forEach(link -> promises.insertPaymentLink(p.id(), link.paymentRef(), link.amount()));
                audit.record("PROMISE_TO_PAY", p.ref(), "PROMISE_" + outcome.status(), actor, outcome.reason());
                counts.merge(outcome.status(), 1, Integer::sum);
                if ("AWAITING_PROMISE".equals(status)) {
                    status = data.position(c.loanId(), asOf).arrears().signum() == 0 ? "RESOLVED" : "IN_PROGRESS";
                    lifecycle.transition(c.id(), c.caseRef(), "AWAITING_PROMISE", status, "Promise " + p.ref() + " " + outcome.status(), actor);
                }
            }
        }
        log.info("Promise reconciliation as of {} by {}: {}", asOf, actor, counts);
        return counts;
    }
}
