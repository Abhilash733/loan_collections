package com.example.loancollections.service.impl;

import com.example.loancollections.repository.jdbc.AuditEventRepository;
import com.example.loancollections.service.AuditService;
import com.example.loancollections.util.DateUtils;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Append-only business audit trail (audit_events). */
@Service
public class AuditServiceImpl implements AuditService {
    private static final int MAX_REASON = 500;
    private final AuditEventRepository repository;

    public AuditServiceImpl(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(String entityType, String entityRef, String action, String actor, String reason) {
        String trimmed = reason == null || reason.length() <= MAX_REASON ? reason : reason.substring(0, MAX_REASON);
        repository.insert("AUD-" + UUID.randomUUID(), entityType, entityRef, action, actor, trimmed,
                DateUtils.toUtcDateTime(Instant.now()));
    }
}
