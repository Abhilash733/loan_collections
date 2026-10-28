-- Small deterministic demo dataset so the UI is useful on first startup.
INSERT IGNORE INTO customers (customer_ref, customer_type) VALUES ('CUS-DEMO-001', 'INDIVIDUAL');

INSERT IGNORE INTO accounts (account_ref, customer_id, status, opened_at)
SELECT 'ACC-DEMO-001', id, 'ACTIVE', '2025-01-15'
FROM customers WHERE customer_ref = 'CUS-DEMO-001';

INSERT IGNORE INTO loans (loan_ref, account_id, principal_amount, annual_interest_rate, term_months, start_date, status)
SELECT 'LN-DEMO-0001', id, 150000.00, 12.5000, 36, '2025-02-01', 'ACTIVE'
FROM accounts WHERE account_ref = 'ACC-DEMO-001';

INSERT IGNORE INTO collection_cases (case_ref, loan_id, status, priority_score, priority_band, reason_codes, opened_at)
SELECT 'CASE-DEMO-0001', id, 'OPEN', 65.00, 'HIGH', 'DEMO_RECORD', '2026-10-01 09:00:00.000000'
FROM loans WHERE loan_ref = 'LN-DEMO-0001';
