package com.example.loancollections.repository.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean existsById(long accountId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE id = ?", Integer.class, accountId);
        return count != null && count > 0;
    }
}