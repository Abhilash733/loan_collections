package com.example.loancollections.service.impl;

import com.example.loancollections.domain.CollectionsData;
import com.example.loancollections.domain.CollectionsData.CaseRow;
import com.example.loancollections.domain.CollectionsRules;
import com.example.loancollections.domain.ContactPolicy;
import com.example.loancollections.domain.ContactPolicy.Preference;
import com.example.loancollections.domain.LoanPosition;
import com.example.loancollections.domain.PriorityScoring;
import com.example.loancollections.domain.PromiseOutcome;
import com.example.loancollections.exception.ResourceNotFoundException;
import com.example.loancollections.model.dto.response.CaseAssessment;
import com.example.loancollections.repository.jdbc.CollectionsDataRepository;
import com.example.loancollections.service.WorkQueueService;
import com.example.loancollections.util.DateUtils;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WorkQueueServiceImpl implements WorkQueueService {
    private static final Set<String> CLOSED_STATUSES = Set.of("RESOLVED", "CLOSED");
    private static final int SLOT_HORIZON_DAYS = 14;
    private final CollectionsDataRepository repository;

    public WorkQueueServiceImpl(CollectionsDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<CaseAssessment> assessAll(LocalDate asOf) {
        return assessAll(repository.load(), asOf);
    }

    @Override
    public List<CaseAssessment> assessAll(CollectionsData data, LocalDate asOf) {
        return data.cases().stream().map(c -> assess(data, c, asOf)).toList();
    }

    @Override
    public List<CaseAssessment> queue(List<CaseAssessment> assessments) {
        return assessments.stream().filter(a -> !"NO_ACTION".equals(a.nextAction()))
                .sorted(Comparator.comparing((CaseAssessment a) -> !a.actionable())
                        .thenComparing(CaseAssessment::priorityScore, Comparator.reverseOrder())
                        .thenComparing(CaseAssessment::daysPastDue, Comparator.reverseOrder())
                        .thenComparing(CaseAssessment::caseRef))
                .toList();
    }

    @Override
    public CaseAssessment assess(String caseRef, LocalDate asOf) {
        CaseRow c = repository.findCase(caseRef)
                .orElseThrow(() -> new ResourceNotFoundException("Collection case not found: " + caseRef));
        return assess(repository.loadForCase(c), c, asOf);
    }

    private CaseAssessment assess(CollectionsData data, CaseRow c, LocalDate asOf) {
        LoanPosition pos = data.position(c.loanId(), asOf);
        List<Preference> prefs = data.preferences(c.accountId());
        Set<String> blocking = new TreeSet<>();
        ContactPolicy.activeRestrictions(prefs, asOf).stream().map(Preference::restrictionType)
                .filter(CollectionsRules.BLOCKING_RESTRICTIONS::contains).forEach(blocking::add);
        boolean hardship = ContactPolicy.hardship(prefs, asOf);
        List<PromiseOutcome> outcomes = data.promises(c.id()).stream()
                .map(p -> PromiseOutcome.evaluate(p.amount(), DateUtils.utcDate(p.createdAt()), p.promiseDate(),
                        data.promisePayments(c.loanId()), asOf)).toList();
        int broken = (int) outcomes.stream().filter(PromiseOutcome::broken).count();
        boolean pending = outcomes.stream().anyMatch(o -> "PENDING".equals(o.status()));
        String riskBand = CollectionsRules.riskBand(pos.daysPastDue(), c.riskGrade());
        PriorityScoring.Score score = PriorityScoring.score(pos.daysPastDue(), pos.arrears(), riskBand, broken, hardship, blocking);
        String action = CollectionsRules.nextAction(c.status(), pos, blocking, pending, hardship);
        boolean inactive = CLOSED_STATUSES.contains(c.status());

        String channel = null;
        Instant slot = null;
        if ("SCHEDULE_CONTACT".equals(action) || "CONTACT_HARDSHIP_SUPPORT".equals(action)) {
            channel = ContactPolicy.channelFor(prefs, asOf).orElse(null);
            Instant startOfDay = asOf.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant from = asOf.equals(DateUtils.todayUtc()) && startOfDay.isBefore(Instant.now()) ? Instant.now() : startOfDay;
            slot = ContactPolicy.nextPermittedSlot(prefs, from, SLOT_HORIZON_DAYS).orElse(null);
        }
        return new CaseAssessment(c.caseRef(), c.loanRef(), asOf, c.status(), pos.daysPastDue(), pos.arrears(), pos.paidOff(),
                riskBand, inactive ? BigDecimal.ZERO.setScale(2) : score.value(), inactive ? "NONE" : score.band(),
                inactive ? "" : score.breakdown(), inactive ? List.of("CASE_NOT_ACTIVE") : score.reasonCodes(),
                blocking, hardship, pending, broken, action, CollectionsRules.expectedBand(c.status(), action, score.band()),
                channel, slot);
    }
}
