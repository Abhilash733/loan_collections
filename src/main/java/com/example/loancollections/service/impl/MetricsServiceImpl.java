package com.example.loancollections.service.impl;

import com.example.loancollections.domain.CollectionsData;
import com.example.loancollections.domain.CollectionsRules;
import com.example.loancollections.model.dto.response.CaseAssessment;
import com.example.loancollections.model.dto.response.MetricsResponse;
import com.example.loancollections.model.dto.response.RuleViolation;
import com.example.loancollections.repository.jdbc.CollectionsDataRepository;
import com.example.loancollections.repository.jdbc.ContactAttemptRepository;
import com.example.loancollections.repository.jdbc.PromiseRepository;
import com.example.loancollections.service.ComplianceService;
import com.example.loancollections.service.MetricsService;
import com.example.loancollections.service.WorkQueueService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MetricsServiceImpl implements MetricsService {
    private final CollectionsDataRepository collectionsData;
    private final ContactAttemptRepository attempts;
    private final PromiseRepository promises;
    private final WorkQueueService workQueue;
    private final ComplianceService compliance;

    public MetricsServiceImpl(CollectionsDataRepository collectionsData, ContactAttemptRepository attempts,
                              PromiseRepository promises, WorkQueueService workQueue, ComplianceService compliance) {
        this.collectionsData = collectionsData;
        this.attempts = attempts;
        this.promises = promises;
        this.workQueue = workQueue;
        this.compliance = compliance;
    }

    @Override
    public MetricsResponse compute(LocalDate asOf) {
        CollectionsData data = collectionsData.load();
        List<CaseAssessment> all = workQueue.assessAll(data, asOf);
        List<CaseAssessment> active = all.stream().filter(a -> CollectionsRules.ACTIVE_CASE_STATUSES.contains(a.caseStatus())).toList();
        Map<String, Long> contacts = attempts.countByOutcome();
        Map<String, Long> promiseCounts = promises.countByStatus();
        long totalAttempts = contacts.values().stream().mapToLong(Long::longValue).sum();
        long settled = promiseCounts.entrySet().stream().filter(e -> !"PENDING".equals(e.getKey()) && !"CANCELLED".equals(e.getKey()))
                .mapToLong(Map.Entry::getValue).sum();
        List<RuleViolation> violations = compliance.detect(data, asOf);
        return new MetricsResponse(asOf, data.loans().size(), active.size(),
                active.stream().map(CaseAssessment::arrears).reduce(BigDecimal.ZERO, BigDecimal::add),
                active.stream().mapToInt(CaseAssessment::daysPastDue).average().orElse(0),
                countBy(active, CaseAssessment::priorityBand), countBy(all, CaseAssessment::nextAction),
                contacts, percent(contacts.getOrDefault("BLOCKED", 0L), totalAttempts),
                promiseCounts, percent(promiseCounts.getOrDefault("FULFILLED", 0L), settled),
                new TreeMap<>(violations.stream().collect(Collectors.groupingBy(RuleViolation::ruleId, Collectors.counting()))),
                workQueue.queue(all));
    }

    private static Map<String, Long> countBy(List<CaseAssessment> rows, Function<CaseAssessment, String> key) {
        return new TreeMap<>(rows.stream().collect(Collectors.groupingBy(key, Collectors.counting())));
    }

    private static BigDecimal percent(long part, long total) {
        return total == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(part * 100).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }
}
