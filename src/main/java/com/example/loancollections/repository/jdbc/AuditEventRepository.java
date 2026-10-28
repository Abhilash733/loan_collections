package com.example.loancollections.repository.jdbc;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only writes to audit_events. */
@Repository
public class AuditEventRepository {
    private final JdbcTemplate jdbc;

    public AuditEventRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(String eventRef, String entityType, String entityRef, String action, String actor,
                       String reason, LocalDateTime occurredAt) {
        jdbc.update("""
                INSERT INTO audit_events (event_ref, entity_type, entity_ref, action, actor_ref, reason, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", eventRef, entityType, entityRef, action, actor, reason, occurredAt);
    }
}