package com.example.loancollections.repository.jdbc;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Case status updates and the append-only case_status_history. */
@Repository
public class CaseStatusRepository {
    private final JdbcTemplate jdbc;

    public CaseStatusRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Optimistic update: succeeds only if the case is still in {@code from}. Returns rows updated. */
    public int updateStatus(long caseId, String from, String to, LocalDateTime closedAt) {
        return jdbc.update("UPDATE collection_cases SET status = ?, closed_at = ? WHERE id = ? AND status = ?",
                to, closedAt, caseId, from);
    }

    public int nextHistorySequence(long caseId) {
        return jdbc.queryForObject("SELECT COUNT(*) + 1 FROM case_status_history WHERE case_id = ?", Integer.class, caseId);
    }

    public void insertHistory(String eventRef, long caseId, String from, String to, String reason, String actor, LocalDateTime at) {
        jdbc.update("""
                INSERT INTO case_status_history (event_ref, case_id, previous_status, new_status, reason, actor_ref, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", eventRef, caseId, from, to, reason, actor, at);
    }
}