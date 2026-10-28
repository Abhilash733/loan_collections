CREATE TABLE customers (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  customer_ref VARCHAR(40) NOT NULL UNIQUE,
  customer_type VARCHAR(20) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT chk_customer_type CHECK (customer_type IN ('INDIVIDUAL','BUSINESS'))
) ENGINE=InnoDB;

CREATE TABLE accounts (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_ref VARCHAR(40) NOT NULL UNIQUE,
  customer_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  opened_at DATE NOT NULL,
  closed_at DATE NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_accounts_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
  CONSTRAINT chk_account_status CHECK (status IN ('ACTIVE','CLOSED','SUSPENDED')),
  CONSTRAINT chk_account_dates CHECK (closed_at IS NULL OR closed_at >= opened_at),
  INDEX idx_accounts_customer (customer_id)
) ENGINE=InnoDB;

CREATE TABLE loans (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  loan_ref VARCHAR(40) NOT NULL UNIQUE,
  account_id BIGINT NOT NULL,
  principal_amount DECIMAL(14,2) NOT NULL,
  annual_interest_rate DECIMAL(7,4) NOT NULL,
  term_months INT NOT NULL,
  start_date DATE NOT NULL,
  status VARCHAR(20) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_loans_account FOREIGN KEY (account_id) REFERENCES accounts(id),
  CONSTRAINT chk_loan_principal CHECK (principal_amount > 0),
  CONSTRAINT chk_loan_rate CHECK (annual_interest_rate >= 0),
  CONSTRAINT chk_loan_term CHECK (term_months > 0),
  CONSTRAINT chk_loan_status CHECK (status IN ('ACTIVE','PAID_OFF','DEFAULTED','CANCELLED')),
  INDEX idx_loans_account_id (account_id)
) ENGINE=InnoDB;

CREATE TABLE installments (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  installment_ref VARCHAR(80) NOT NULL UNIQUE,
  loan_id BIGINT NOT NULL,
  installment_number INT NOT NULL,
  due_date DATE NOT NULL,
  principal_due DECIMAL(14,2) NOT NULL,
  interest_due DECIMAL(14,2) NOT NULL,
  fee_due DECIMAL(14,2) NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_installments_loan FOREIGN KEY (loan_id) REFERENCES loans(id),
  CONSTRAINT uq_installment_sequence UNIQUE (loan_id, installment_number),
  CONSTRAINT chk_installment_number CHECK (installment_number > 0),
  CONSTRAINT chk_installment_amounts CHECK (principal_due >= 0 AND interest_due >= 0 AND fee_due >= 0),
  CONSTRAINT chk_installment_status CHECK (status IN ('SCHEDULED','PARTIALLY_PAID','PAID','OVERDUE')),
  INDEX idx_installments_due (due_date, loan_id)
) ENGINE=InnoDB;

CREATE TABLE payments (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  payment_ref VARCHAR(40) NOT NULL UNIQUE,
  loan_id BIGINT NOT NULL,
  amount DECIMAL(14,2) NOT NULL,
  received_at TIMESTAMP(6) NOT NULL,
  status VARCHAR(20) NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL UNIQUE,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_payments_loan FOREIGN KEY (loan_id) REFERENCES loans(id),
  CONSTRAINT chk_payment_amount CHECK (amount > 0),
  CONSTRAINT chk_payment_status CHECK (status IN ('PENDING','SUCCEEDED','FAILED','REVERSED')),
  INDEX idx_payments_loan_received (loan_id, received_at)
) ENGINE=InnoDB;

CREATE TABLE payment_allocations (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  allocation_ref VARCHAR(60) NOT NULL UNIQUE,
  payment_id BIGINT NOT NULL,
  installment_id BIGINT NOT NULL,
  amount DECIMAL(14,2) NOT NULL,
  status VARCHAR(20) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_alloc_payment FOREIGN KEY (payment_id) REFERENCES payments(id),
  CONSTRAINT fk_alloc_installment FOREIGN KEY (installment_id) REFERENCES installments(id),
  CONSTRAINT chk_allocation_amount CHECK (amount > 0),
  CONSTRAINT chk_allocation_status CHECK (status IN ('ACTIVE','REVERSED')),
  INDEX idx_allocation_installment (installment_id)
) ENGINE=InnoDB;

CREATE TABLE delinquency_snapshots (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  snapshot_ref VARCHAR(50) NOT NULL UNIQUE,
  loan_id BIGINT NOT NULL,
  as_of_date DATE NOT NULL,
  days_past_due INT NOT NULL,
  arrears_amount DECIMAL(14,2) NOT NULL,
  risk_band VARCHAR(16) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_snapshot_loan FOREIGN KEY (loan_id) REFERENCES loans(id),
  CONSTRAINT uq_snapshot_date UNIQUE (loan_id, as_of_date),
  CONSTRAINT chk_snapshot_values CHECK (days_past_due >= 0 AND arrears_amount >= 0),
  INDEX idx_snapshot_dpd (as_of_date, days_past_due)
) ENGINE=InnoDB;

CREATE TABLE contact_preferences (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  preference_ref VARCHAR(50) NOT NULL UNIQUE,
  account_id BIGINT NOT NULL,
  channel VARCHAR(20) NOT NULL,
  effective_from DATE NOT NULL,
  effective_to DATE NULL,
  permitted_start TIME NULL,
  permitted_end TIME NULL,
  restriction_type VARCHAR(30) NOT NULL DEFAULT 'NONE',
  timezone_id VARCHAR(60) NOT NULL DEFAULT 'Asia/Kolkata',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_preference_account FOREIGN KEY (account_id) REFERENCES accounts(id),
  CONSTRAINT chk_contact_channel CHECK (channel IN ('PHONE','SMS','EMAIL','LETTER')),
  CONSTRAINT chk_restriction_type CHECK (restriction_type IN ('NONE','DO_NOT_CONTACT','DISPUTE','HARDSHIP','LEGAL_HOLD')),
  CONSTRAINT chk_preference_dates CHECK (effective_to IS NULL OR effective_to >= effective_from),
  INDEX idx_preference_account_effective (account_id, effective_from, effective_to)
) ENGINE=InnoDB;

CREATE TABLE collection_cases (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  case_ref VARCHAR(50) NOT NULL UNIQUE,
  loan_id BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL,
  priority_score DECIMAL(5,2) NOT NULL,
  priority_band VARCHAR(16) NOT NULL,
  reason_codes VARCHAR(500) NOT NULL DEFAULT '',
  opened_at TIMESTAMP(6) NOT NULL,
  closed_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_case_loan FOREIGN KEY (loan_id) REFERENCES loans(id),
  CONSTRAINT chk_case_status CHECK (status IN ('OPEN','IN_PROGRESS','AWAITING_PROMISE','RESOLVED','CLOSED','DISPUTED')),
  CONSTRAINT chk_case_score CHECK (priority_score >= 0 AND priority_score <= 100),
  INDEX idx_cases_loan_status (loan_id, status),
  INDEX idx_cases_priority (status, priority_score)
) ENGINE=InnoDB;

CREATE TABLE contact_attempts (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  contact_ref VARCHAR(60) NOT NULL UNIQUE,
  case_id BIGINT NOT NULL,
  channel VARCHAR(20) NOT NULL,
  scheduled_at TIMESTAMP(6) NOT NULL,
  attempted_at TIMESTAMP(6) NULL,
  outcome VARCHAR(30) NOT NULL,
  blocked_reason VARCHAR(100) NULL,
  actor_ref VARCHAR(80) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_attempt_case FOREIGN KEY (case_id) REFERENCES collection_cases(id),
  CONSTRAINT chk_attempt_channel CHECK (channel IN ('PHONE','SMS','EMAIL','LETTER')),
  INDEX idx_attempt_case_time (case_id, scheduled_at)
) ENGINE=InnoDB;

CREATE TABLE promises_to_pay (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  promise_ref VARCHAR(60) NOT NULL UNIQUE,
  case_id BIGINT NOT NULL,
  promised_amount DECIMAL(14,2) NOT NULL,
  promise_date DATE NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  status VARCHAR(24) NOT NULL,
  outcome_reason VARCHAR(250) NULL,
  CONSTRAINT fk_promise_case FOREIGN KEY (case_id) REFERENCES collection_cases(id),
  CONSTRAINT chk_promise_amount CHECK (promised_amount > 0),
  CONSTRAINT chk_promise_status CHECK (status IN ('PENDING','FULFILLED','PARTIALLY_FULFILLED','MISSED','CANCELLED')),
  INDEX idx_promise_case_date (case_id, promise_date)
) ENGINE=InnoDB;

CREATE TABLE promise_payment_links (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  promise_id BIGINT NOT NULL,
  payment_id BIGINT NOT NULL,
  qualifying_amount DECIMAL(14,2) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_ppl_promise FOREIGN KEY (promise_id) REFERENCES promises_to_pay(id),
  CONSTRAINT fk_ppl_payment FOREIGN KEY (payment_id) REFERENCES payments(id),
  CONSTRAINT uq_promise_payment UNIQUE (promise_id, payment_id),
  CONSTRAINT chk_qualifying_amount CHECK (qualifying_amount >= 0)
) ENGINE=InnoDB;

CREATE TABLE case_status_history (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  case_id BIGINT NOT NULL,
  previous_status VARCHAR(24) NULL,
  new_status VARCHAR(24) NOT NULL,
  reason VARCHAR(250) NOT NULL,
  actor_ref VARCHAR(80) NOT NULL,
  occurred_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT fk_history_case FOREIGN KEY (case_id) REFERENCES collection_cases(id),
  INDEX idx_history_case_time (case_id, occurred_at)
) ENGINE=InnoDB;

CREATE TABLE audit_events (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  event_ref VARCHAR(60) NOT NULL UNIQUE,
  entity_type VARCHAR(50) NOT NULL,
  entity_ref VARCHAR(80) NOT NULL,
  action VARCHAR(60) NOT NULL,
  actor_ref VARCHAR(80) NOT NULL,
  reason VARCHAR(500) NULL,
  occurred_at TIMESTAMP(6) NOT NULL,
  INDEX idx_audit_entity (entity_type, entity_ref, occurred_at)
) ENGINE=InnoDB;
