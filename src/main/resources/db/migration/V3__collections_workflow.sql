-- Columns and constraints needed by the collections workflow and the synthetic dataset.

ALTER TABLE customers
  ADD COLUMN synthetic_label VARCHAR(80) NULL,
  ADD COLUMN risk_grade CHAR(1) NOT NULL DEFAULT 'B',
  ADD CONSTRAINT chk_customer_risk_grade CHECK (risk_grade IN ('A','B','C','D'));

-- Restriction rows (DO_NOT_CONTACT, LEGAL_HOLD, DISPUTE, HARDSHIP) apply to every channel: channel is NULL.
ALTER TABLE contact_preferences MODIFY channel VARCHAR(20) NULL;

ALTER TABLE delinquency_snapshots
  ADD CONSTRAINT chk_snapshot_risk_band CHECK (risk_band IN ('LOW','MEDIUM','HIGH'));

ALTER TABLE collection_cases
  ADD COLUMN score_breakdown VARCHAR(200) NOT NULL DEFAULT '',
  ADD CONSTRAINT chk_case_priority_band CHECK (priority_band IN ('NONE','LOW','MEDIUM','HIGH','INELIGIBLE'));

ALTER TABLE case_status_history
  ADD COLUMN event_ref VARCHAR(60) NULL,
  ADD CONSTRAINT uq_history_event_ref UNIQUE (event_ref);

ALTER TABLE contact_attempts
  ADD CONSTRAINT chk_attempt_outcome CHECK (outcome IN ('SCHEDULED','BLOCKED','NO_RESPONSE','CONTACTED','PROMISE_MADE','DISPUTE_RAISED'));

ALTER TABLE promises_to_pay
  ADD COLUMN contact_attempt_id BIGINT NULL,
  ADD CONSTRAINT fk_promise_contact FOREIGN KEY (contact_attempt_id) REFERENCES contact_attempts(id);
