package com.example.loancollections.repository.jdbc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PromiseRepository {
    private final JdbcTemplate jdbc;

    public PromiseRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public int nextSequence(long caseId) {
        return jdbc.queryForObject("SELECT COUNT(*) + 1 FROM promises_to_pay WHERE case_id = ?", Integer.class, caseId);
    }

    public void insert(String promiseRef, long caseId, Long contactAttemptId, BigDecimal amount, LocalDate promiseDate, LocalDateTime createdAt) {
        jdbc.update("""
                INSERT INTO promises_to_pay (promise_ref, case_id, contact_attempt_id, promised_amount, promise_date, created_at, status, outcome_reason)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 'Deadline has not passed')""",
                promiseRef, caseId, contactAttemptId, amount, promiseDate, createdAt);
    }

    public void updateOutcome(long promiseId, String status, String reason) {
        jdbc.update("UPDATE promises_to_pay SET status = ?, outcome_reason = ? WHERE id = ?", status, reason, promiseId);
    }

    public void insertPaymentLink(long promiseId, String paymentRef, BigDecimal qualifyingAmount) {
        jdbc.update("""
                INSERT IGNORE INTO promise_payment_links (promise_id, payment_id, qualifying_amount)
                SELECT ?, id, ? FROM payments WHERE payment_ref = ?""", promiseId, qualifyingAmount, paymentRef);
    }

    public Map<String, Long> countByStatus() {
        Map<String, Long> out = new TreeMap<>();
        jdbc.query("SELECT status, COUNT(*) FROM promises_to_pay GROUP BY status", rs -> { out.put(rs.getString(1), rs.getLong(2)); });
        return out;
    }
}