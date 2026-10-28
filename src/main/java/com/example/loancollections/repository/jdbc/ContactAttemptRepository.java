package com.example.loancollections.repository.jdbc;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ContactAttemptRepository {
    private final JdbcTemplate jdbc;

    public ContactAttemptRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Attempt(long id, long caseId, String caseRef, String channel, LocalDateTime scheduledAt, String outcome) {}

    public Optional<Attempt> findByRef(String contactRef) {
        return jdbc.query("""
                SELECT a.id, a.case_id, c.case_ref, a.channel, a.scheduled_at, a.outcome FROM contact_attempts a
                JOIN collection_cases c ON c.id = a.case_id WHERE a.contact_ref = ?""",
                (rs, i) -> new Attempt(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, LocalDateTime.class), rs.getString(6)), contactRef).stream().findFirst();
    }

    public int nextSequence(long caseId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(contact_ref, '-', -1) AS UNSIGNED)), 0) + 1
                FROM contact_attempts WHERE case_id = ?""", Integer.class, caseId);
    }

    public void insert(String contactRef, long caseId, String channel, LocalDateTime scheduledAt, String outcome,
                       String blockedReason, String actor) {
        jdbc.update("""
                INSERT INTO contact_attempts (contact_ref, case_id, channel, scheduled_at, outcome, blocked_reason, actor_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", contactRef, caseId, channel, scheduledAt, outcome, blockedReason, actor);
    }

    public void markBlocked(String contactRef, String reason) {
        jdbc.update("UPDATE contact_attempts SET outcome = 'BLOCKED', blocked_reason = ? WHERE contact_ref = ?", reason, contactRef);
    }

    public void recordOutcome(String contactRef, LocalDateTime attemptedAt, String outcome, String actor) {
        jdbc.update("UPDATE contact_attempts SET attempted_at = ?, outcome = ?, actor_ref = ? WHERE contact_ref = ?",
                attemptedAt, outcome, actor, contactRef);
    }

    public void markPromiseMade(long id, LocalDateTime attemptedAt, String actor) {
        jdbc.update("UPDATE contact_attempts SET outcome = 'PROMISE_MADE', attempted_at = COALESCE(attempted_at, ?), actor_ref = ? WHERE id = ?",
                attemptedAt, actor, id);
    }

    public Map<String, Long> countByOutcome() {
        Map<String, Long> out = new TreeMap<>();
        jdbc.query("SELECT outcome, COUNT(*) FROM contact_attempts GROUP BY outcome", rs -> { out.put(rs.getString(1), rs.getLong(2)); });
        return out;
    }
}