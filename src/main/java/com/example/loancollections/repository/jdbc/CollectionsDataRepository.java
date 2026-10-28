package com.example.loancollections.repository.jdbc;

import com.example.loancollections.domain.CollectionsData;
import com.example.loancollections.domain.CollectionsData.*;
import com.example.loancollections.domain.ContactPolicy.Preference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC reads for the collections workflow. Timestamps are UTC (the connection forces the session time zone). */
@Repository
public class CollectionsDataRepository {
    private static final String CASE_SQL = """
            SELECT c.id, c.case_ref, c.loan_id, l.loan_ref, l.account_id, c.status, cu.risk_grade,
                   c.priority_score, c.priority_band, c.score_breakdown, c.reason_codes
            FROM collection_cases c JOIN loans l ON l.id = c.loan_id
            JOIN accounts a ON a.id = l.account_id JOIN customers cu ON cu.id = a.customer_id""";

    private final JdbcTemplate jdbc;

    public CollectionsDataRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public CollectionsData load() {
        List<CaseRow> cases = jdbc.query(CASE_SQL + " ORDER BY c.id", this::caseRow);
        List<LoanRow> loans = jdbc.query("""
                SELECT l.id, l.loan_ref, l.account_id, l.status, cu.risk_grade FROM loans l
                JOIN accounts a ON a.id = l.account_id JOIN customers cu ON cu.id = a.customer_id""",
                (rs, i) -> new LoanRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5)));
        List<InstallmentRow> installments = jdbc.query("""
                SELECT loan_id, installment_ref, installment_number, due_date, principal_due + interest_due + fee_due, status
                FROM installments ORDER BY loan_id, installment_number""",
                (rs, i) -> new InstallmentRow(rs.getLong(1), rs.getString(2), rs.getInt(3), date(rs, 4), rs.getBigDecimal(5), rs.getString(6)));
        List<AllocationRow> allocations = jdbc.query(VALID_ALLOCATIONS + " ORDER BY p.received_at, a.id", this::allocationRow);
        List<PaymentRow> payments = jdbc.query("SELECT payment_ref, loan_id, amount, received_at, status FROM payments ORDER BY id",
                (rs, i) -> new PaymentRow(rs.getString(1), rs.getLong(2), rs.getBigDecimal(3), instant(rs, 4).atZone(ZoneOffset.UTC).toLocalDate(), rs.getString(5)));
        List<PromiseRow> promises = jdbc.query(PROMISE_SQL + " ORDER BY id", this::promiseRow);
        List<AttemptRow> attempts = jdbc.query("""
                SELECT contact_ref, case_id, channel, scheduled_at, attempted_at, outcome FROM contact_attempts ORDER BY id""",
                (rs, i) -> new AttemptRow(rs.getString(1), rs.getLong(2), rs.getString(3), instant(rs, 4), instant(rs, 5), rs.getString(6)));
        List<HistoryRow> history = jdbc.query("""
                SELECT COALESCE(event_ref, CONCAT('history#', id)), case_id, previous_status, new_status, occurred_at
                FROM case_status_history ORDER BY case_id, occurred_at, id""",
                (rs, i) -> new HistoryRow(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), instant(rs, 5)));
        List<SnapshotRow> snapshots = jdbc.query("""
                SELECT snapshot_ref, loan_id, as_of_date, days_past_due, arrears_amount, risk_band FROM delinquency_snapshots""",
                (rs, i) -> new SnapshotRow(rs.getString(1), rs.getLong(2), date(rs, 3), rs.getInt(4), rs.getBigDecimal(5), rs.getString(6)));
        return new CollectionsData(cases, loans, installments, allocations, payments, allPreferences(),
                promises, attempts, history, snapshots);
    }

    private static final String VALID_ALLOCATIONS = """
            SELECT a.allocation_ref, i.loan_id, i.installment_number, a.amount, p.received_at
            FROM payment_allocations a JOIN payments p ON p.id = a.payment_id JOIN installments i ON i.id = a.installment_id
            WHERE a.status = 'ACTIVE' AND p.status = 'SUCCEEDED'""";
    private static final String PROMISE_SQL =
            "SELECT id, promise_ref, case_id, promised_amount, promise_date, created_at, status FROM promises_to_pay";
    private static final String PREFERENCE_SQL = """
            SELECT preference_ref, account_id, channel, effective_from, effective_to, permitted_start, permitted_end,
                   restriction_type, timezone_id FROM contact_preferences""";

    public Optional<CaseRow> findCase(String caseRef) {
        return jdbc.query(CASE_SQL + " WHERE c.case_ref = ?", this::caseRow, caseRef).stream().findFirst();
    }

    /** Data for a single case, for write-side validation. */
    public CollectionsData loadForCase(CaseRow c) {
        List<LoanRow> loans = List.of(new LoanRow(c.loanId(), c.loanRef(), c.accountId(), "", c.riskGrade()));
        List<InstallmentRow> installments = jdbc.query("""
                SELECT loan_id, installment_ref, installment_number, due_date, principal_due + interest_due + fee_due, status
                FROM installments WHERE loan_id = ? ORDER BY installment_number""",
                (rs, i) -> new InstallmentRow(rs.getLong(1), rs.getString(2), rs.getInt(3), date(rs, 4), rs.getBigDecimal(5), rs.getString(6)), c.loanId());
        List<AllocationRow> allocations = jdbc.query(VALID_ALLOCATIONS + " AND i.loan_id = ?", this::allocationRow, c.loanId());
        List<PaymentRow> payments = jdbc.query("SELECT payment_ref, loan_id, amount, received_at, status FROM payments WHERE loan_id = ?",
                (rs, i) -> new PaymentRow(rs.getString(1), rs.getLong(2), rs.getBigDecimal(3), instant(rs, 4).atZone(ZoneOffset.UTC).toLocalDate(), rs.getString(5)), c.loanId());
        List<Preference> prefs = jdbc.query(PREFERENCE_SQL + " WHERE account_id = ? ORDER BY id", this::preferenceRow, c.accountId());
        List<PromiseRow> promises = jdbc.query(PROMISE_SQL + " WHERE case_id = ? ORDER BY id", this::promiseRow, c.id());
        return new CollectionsData(List.of(c), loans, installments, allocations, payments, Map.of(c.accountId(), prefs),
                promises, List.of(), List.of(), List.of());
    }

    private Map<Long, List<Preference>> allPreferences() {
        record Row(long accountId, Preference preference) {}
        return jdbc.query(PREFERENCE_SQL + " ORDER BY id", (rs, i) -> new Row(rs.getLong(2), preferenceRow(rs, i))).stream()
                .collect(Collectors.groupingBy(Row::accountId, Collectors.mapping(Row::preference, Collectors.toList())));
    }

    private CaseRow caseRow(ResultSet rs, int i) throws SQLException {
        return new CaseRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getLong(5), rs.getString(6),
                rs.getString(7), rs.getBigDecimal(8), rs.getString(9), rs.getString(10), rs.getString(11));
    }

    private AllocationRow allocationRow(ResultSet rs, int i) throws SQLException {
        return new AllocationRow(rs.getString(1), rs.getLong(2), rs.getInt(3), rs.getBigDecimal(4),
                instant(rs, 5).atZone(ZoneOffset.UTC).toLocalDate());
    }

    private PromiseRow promiseRow(ResultSet rs, int i) throws SQLException {
        return new PromiseRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getBigDecimal(4), date(rs, 5), instant(rs, 6), rs.getString(7));
    }

    private Preference preferenceRow(ResultSet rs, int i) throws SQLException {
        return new Preference(rs.getString(1), rs.getString(3), date(rs, 4), date(rs, 5),
                rs.getObject(6, LocalTime.class), rs.getObject(7, LocalTime.class), rs.getString(8), rs.getString(9));
    }

    static LocalDate date(ResultSet rs, int col) throws SQLException {
        return rs.getObject(col, LocalDate.class);
    }

    static Instant instant(ResultSet rs, int col) throws SQLException {
        LocalDateTime value = rs.getObject(col, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
