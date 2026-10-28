package com.example.loancollections.service.impl;

import com.example.loancollections.domain.CollectionsRules;
import com.example.loancollections.exception.BusinessException;
import com.example.loancollections.repository.jdbc.CaseStatusRepository;
import com.example.loancollections.service.AuditService;
import com.example.loancollections.service.CaseLifecycleService;
import com.example.loancollections.util.DateUtils;
import java.time.Instant;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CaseLifecycleServiceImpl implements CaseLifecycleService {
    private final CaseStatusRepository repository;
    private final AuditService audit;

    public CaseLifecycleServiceImpl(CaseStatusRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Override
    @Transactional
    public void transition(long caseId, String caseRef, String from, String to, String reason, String actor) {
        if (!CollectionsRules.isAllowedTransition(from, to))
            throw new BusinessException("Transition " + from + " -> " + to + " is not allowed for case " + caseRef);
        LocalDateTime now = DateUtils.toUtcDateTime(Instant.now());
        if (repository.updateStatus(caseId, from, to, "CLOSED".equals(to) ? now : null) != 1)
            throw new BusinessException("Case " + caseRef + " is no longer " + from);
        String eventRef = "CSH-%s-%03d".formatted(caseRef, repository.nextHistorySequence(caseId));
        repository.insertHistory(eventRef, caseId, from, to, reason, actor, now);
        audit.record("COLLECTION_CASE", caseRef, "STATUS_" + from + "_TO_" + to, actor, reason);
    }
}
