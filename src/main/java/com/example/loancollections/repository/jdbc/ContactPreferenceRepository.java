package com.example.loancollections.repository.jdbc;

import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ContactPreferenceRepository {
    private final JdbcTemplate jdbc;

    public ContactPreferenceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Next reference in the account's CPF-&lt;account&gt;-NN sequence. */
    public String nextRef(long accountId) {
        return jdbc.queryForObject("""
                SELECT CONCAT('CPF-', a.account_ref, '-', LPAD(COUNT(p.id) + 1, 2, '0')) FROM accounts a
                LEFT JOIN contact_preferences p ON p.account_id = a.id WHERE a.id = ? GROUP BY a.account_ref""", String.class, accountId);
    }

    /** Account-level restriction applying to all channels, open-ended until reviewed. */
    public void insertRestriction(String ref, long accountId, LocalDate effectiveFrom, String restrictionType, String timezoneId) {
        jdbc.update("""
                INSERT INTO contact_preferences (preference_ref, account_id, channel, effective_from, restriction_type, timezone_id)
                VALUES (?, ?, NULL, ?, ?, ?)""", ref, accountId, effectiveFrom, restrictionType, timezoneId);
    }
}