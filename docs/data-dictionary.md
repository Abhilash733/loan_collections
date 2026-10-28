# Data dictionary

Applies to the CSV/JSON exports (`data/generated/csv`, `json`) and the MySQL schema (`V1`-`V3` Flyway migrations).
Exports identify rows by immutable business references (`*_ref`); the database adds a surrogate `id BIGINT`
primary key and resolves every `*_ref` foreign key to an `*_id` column (`seed.sql` does this mapping).

Conventions:
- **Money**: `DECIMAL(14,2)`, Indian rupees (INR), never floating point. Rates: `DECIMAL(7,4)`, percent per annum.
- **Dates**: ISO `YYYY-MM-DD`. **Timestamps**: UTC, ISO `YYYY-MM-DDTHH:MM:SSZ` in exports, `TIMESTAMP(6)` in MySQL.
- **Times** (contact windows): local time of the preference's `timezone_id`, `HH:MM:SS`; end is exclusive.
- **Empty** CSV cell = not applicable (`NULL` in SQL, `null` in JSON).
- Req = required. Uniq = unique. "Derived" fields are calculated from source records by `tools/collections_rules.py`
  (and identically by the Java application); they are never generated independently.

## Relationships and cardinality

| Parent | Child | Cardinality | Notes |
|---|---|---|---|
| Customer | Account | 1:N | ~15% of new accounts belong to an existing customer |
| Account | Loan | 1:N | ~8% of loans share an account |
| Account | ContactPreference | 1:N | one base preference + optional effective-dated restrictions |
| Loan | Installment | 1:N | 2-4 installments (exactly 3 on average) |
| Loan | Payment | 1:N (0..N) | |
| Payment | Installment | N:M via **PaymentAllocation** | waterfall: oldest outstanding installment first |
| Loan | DelinquencySnapshot | 1:N | month-ends + reference date |
| Loan | CollectionCase | 1:0..1 | opened when DPD reaches 15 |
| CollectionCase | CaseStatusHistory | 1:N | append-only lifecycle events |
| CollectionCase | ContactAttempt | 1:N (0..N) | |
| CollectionCase | PromiseToPay | 1:N (0..N) | |
| ContactAttempt | PromiseToPay | 1:0..1 | optional `source_contact_ref` |
| PromiseToPay | Payment | N:M via **PromisePaymentLink** | qualifying payments |

## Customer (`customers`)
| Field | Type | Req | Allowed values / units | Uniq | Validation |
|---|---|---|---|---|---|
| customer_ref | VARCHAR(40) | Y | `CUS-2026-NNNNNN` | Y | regex, immutable |
| customer_type | VARCHAR(20) | Y | INDIVIDUAL, BUSINESS | | enum |
| synthetic_label | VARCHAR(80) | N | fictional name + `(synthetic NNNNNN)` | | never real personal data |
| risk_grade | CHAR(1) | Y | A (best) .. D (worst) | | enum; drives synthetic payment behaviour and risk band |

## Account (`accounts`)
| Field | Type | Req | Allowed values / units | Uniq | Validation |
|---|---|---|---|---|---|
| account_ref | VARCHAR(40) | Y | `ACC-2026-NNNNNN` | Y | regex |
| customer_ref | FK | Y | customers | | FK exists |
| status | VARCHAR(20) | Y | ACTIVE, CLOSED, SUSPENDED | | Derived: CLOSED iff all loans PAID_OFF |
| opened_at | DATE | Y | | | <= start_date of every loan |
| closed_at | DATE | N | | | present iff CLOSED; >= opened_at; = last payoff date |

## Loan (`loans`)
| Field | Type | Req | Allowed values / units | Uniq | Validation |
|---|---|---|---|---|---|
| loan_ref | VARCHAR(40) | Y | `LOAN-2026-NNNNNN` | Y | regex |
| account_ref | FK | Y | accounts | | FK exists |
| principal_amount | DECIMAL(14,2) | Y | INR 15,000-750,000 (log-normal, multiples of 500) | | > 0; = sum of installment principal_due |
| annual_interest_rate | DECIMAL(7,4) | Y | % p.a.; A 9.5-12.5, B 12.5-16, C 16-21, D 21-28 | | >= 0 |
| term_months | INT | Y | 2, 3, 4 | | = number of installments |
| start_date | DATE | Y | within the 12-month observation window | | day of month <= 28 |
| status | VARCHAR(20) | Y | ACTIVE, PAID_OFF, DEFAULTED, CANCELLED | | Derived at reference date: PAID_OFF if nothing outstanding, DEFAULTED if DPD >= 90 |

## Installment (`installments`)
| Field | Type | Req | Allowed values / units | Uniq | Validation |
|---|---|---|---|---|---|
| installment_ref | VARCHAR(80) | Y | `INS-<loan_ref>-NNN` | Y | prefix = own loan_ref |
| loan_ref | FK | Y | loans | | FK exists |
| installment_number | INT | Y | 1..term_months | (loan, number) | > 0 |
| due_date | DATE | Y | start_date + n months | | strictly increasing; first > start_date |
| principal_due | DECIMAL(14,2) | Y | INR | | >= 0; equal-instalment amortisation, last absorbs rounding |
| interest_due | DECIMAL(14,2) | Y | INR | | >= 0; remaining principal x rate/12 |
| fee_due | DECIMAL(14,2) | Y | INR (0.00 in baseline) | | >= 0 |
| status | VARCHAR(20) | Y | SCHEDULED, PARTIALLY_PAID, PAID, OVERDUE | | Derived from net valid allocations at reference date |

## Payment (`payments`)
| Field | Type | Req | Allowed values / units | Uniq | Validation |
|---|---|---|---|---|---|
| payment_ref | VARCHAR(40) | Y | `PMT-2026-NNNNNN` | Y | regex |
| loan_ref | FK | Y | loans | | FK exists |
| amount | DECIMAL(14,2) | Y | INR | | > 0; capped at the loan's outstanding balance |
| received_at | TIMESTAMP | Y | UTC | | start_date <= date <= reference date |
| status | VARCHAR(20) | Y | PENDING, SUCCEEDED, FAILED, REVERSED | | FAILED has no allocations; REVERSED has only REVERSED allocations |
| idempotency_key | VARCHAR(100) | Y | `IDK-<loan_ref>-NNN` | Y | duplicate check |

## PaymentAllocation (`payment_allocations`) - bridge Payment N:M Installment
| Field | Type | Req | Allowed values / units | Uniq | Validation |
|---|---|---|---|---|---|
| allocation_ref | VARCHAR(60) | Y | `ALC-<payment_ref>-NN` | Y | prefix = own payment_ref |
| payment_ref | FK | Y | payments | | FK exists |
| installment_ref | FK | Y | installments | | same loan as the payment |
| amount | DECIMAL(14,2) | Y | INR | | > 0; sum per payment = payment amount |
| status | VARCHAR(20) | Y | ACTIVE, REVERSED | | ACTIVE iff payment SUCCEEDED |

## DelinquencySnapshot (`delinquency_snapshots`)
| Field | Type | Req | Allowed values / units | Uniq | Validation |
|---|---|---|---|---|---|
| snapshot_ref | VARCHAR(50) | Y | `DS-<loan_ref>-YYYYMMDD` | Y | |
| loan_ref | FK | Y | loans | (loan, as_of_date) | FK exists |
| as_of_date | DATE | Y | month-ends from first due date, plus the reference date; stops after payoff | | <= reference date |
| days_past_due | INT | Y | days | | Derived: days since oldest overdue unpaid installment; >= 0 |
| arrears_amount | DECIMAL(14,2) | Y | INR | | Derived: unpaid amount of installments due before as_of_date; >= 0 |
| risk_band | VARCHAR(16) | Y | LOW, MEDIUM, HIGH | | Derived from DPD and risk_grade |

## ContactPreference (`contact_preferences`) - effective-dated
| Field | Type | Req | Allowed values / units | Uniq | Validation |
|---|---|---|---|---|---|
| preference_ref | VARCHAR(50) | Y | `CPF-<account_ref>-NN` | Y | |
| account_ref | FK | Y | accounts | | FK exists |
| channel | VARCHAR(20) | base only | PHONE, SMS, EMAIL, LETTER; empty for restrictions (= all channels) | | enum |
| effective_from | DATE | Y | | | |
| effective_to | DATE | N | inclusive end; empty = open-ended | | >= effective_from |
| permitted_start / permitted_end | TIME | base only | local time; 08:00-20:00 global limit also applies | | start < end |
| restriction_type | VARCHAR(30) | Y | NONE (base), DO_NOT_CONTACT, LEGAL_HOLD, DISPUTE, HARDSHIP | | enum |
| timezone_id | VARCHAR(60) | Y | Asia/Kolkata, Asia/Dubai | | IANA zone |

## CollectionCase (`collection_cases`)
| Field | Type | Req | Allowed values / units | Uniq | Validation |
|---|---|---|---|---|---|
| case_ref | VARCHAR(50) | Y | `COL-2026-NNNNNN` | Y | regex |
| loan_ref | FK | Y | loans | | FK exists |
| status | VARCHAR(24) | Y | OPEN, IN_PROGRESS, AWAITING_PROMISE, DISPUTED, RESOLVED, CLOSED | | = latest history event |
| priority_score | DECIMAL(5,2) | Y | 0-100 | | Derived at reference date; = sum of score_breakdown |
| priority_band | VARCHAR(16) | Y | NONE, LOW, MEDIUM, HIGH, INELIGIBLE | | HIGH >= 60, MEDIUM >= 30; NONE when RESOLVED/CLOSED |
| score_breakdown | VARCHAR(200) | Y | `DPD=..;BALANCE=..;RISK=..;PROMISES=..[;CAP=..][;GATE=..]` | | explains the score |
| reason_codes | VARCHAR(500) | Y | e.g. DPD_60_PLUS;ARREARS_50K_PLUS;RISK_HIGH;RESTRICTED_DISPUTE | | non-empty |
| opened_at | TIMESTAMP | Y | UTC | | after first due date (DPD reached 15) |
| closed_at | TIMESTAMP | N | UTC | | present iff CLOSED |

## CaseStatusHistory (`case_status_history`) - append-only
| Field | Type | Req | Allowed values / units | Uniq | Validation |
|---|---|---|---|---|---|
| event_ref | VARCHAR(60) | Y | `CSH-<case_ref>-NNN` | Y | |
| case_ref | FK | Y | collection_cases | | FK exists |
| previous_status | VARCHAR(24) | N | empty only for the first (OPEN) event | | = previous event's new_status |
| new_status | VARCHAR(24) | Y | case statuses | | allowed transition (see business rules) |
| reason, actor_ref | VARCHAR | Y | free text / system or user id | | |
| occurred_at | TIMESTAMP | Y | UTC | | non-decreasing per case |

## ContactAttempt (`contact_attempts`)
| Field | Type | Req | Allowed values / units | Uniq | Validation |
|---|---|---|---|---|---|
| contact_ref | VARCHAR(60) | Y | `CTP-<case_ref>-NNN` | Y | prefix = own case_ref |
| case_ref | FK | Y | collection_cases | | FK exists |
| channel | VARCHAR(20) | Y | PHONE, SMS, EMAIL, LETTER | | EMAIL substituted for PHONE/SMS during hardship |
| scheduled_at | TIMESTAMP | Y | UTC | | inside permitted local window, not Sunday |
| attempted_at | TIMESTAMP | N | UTC | | empty iff BLOCKED (or still SCHEDULED); >= scheduled_at |
| outcome | VARCHAR(30) | Y | SCHEDULED, BLOCKED, NO_RESPONSE, CONTACTED, PROMISE_MADE, DISPUTE_RAISED | | enum |
| blocked_reason | VARCHAR(100) | N | restriction type + preference_ref, or window reason | | present iff BLOCKED |
| actor_ref | VARCHAR(80) | Y | agent, NOTIFIER-SERVICE, POLICY-ENGINE, user | | |

## PromiseToPay (`promises_to_pay`)
| Field | Type | Req | Allowed values / units | Uniq | Validation |
|---|---|---|---|---|---|
| promise_ref | VARCHAR(60) | Y | `PTP-<case_ref>-NNN` | Y | |
| case_ref | FK | Y | collection_cases | | FK exists |
| source_contact_ref | FK | N | contact_attempts with outcome PROMISE_MADE | | same case |
| promised_amount | DECIMAL(14,2) | Y | INR | | > 0 and <= arrears on the creation date |
| promise_date | DATE | Y | 3-14 days after creation (<= 30 in the app) | | >= created date |
| created_at | TIMESTAMP | Y | UTC | | |
| status | VARCHAR(24) | Y | PENDING, FULFILLED, PARTIALLY_FULFILLED, MISSED, CANCELLED | | Derived from qualifying payments |
| outcome_reason | VARCHAR(250) | N | "Qualifying payments X of Y by D" | | |

## PromisePaymentLink (`promise_payment_links`) - bridge PromiseToPay N:M Payment
| Field | Type | Req | Allowed values / units | Uniq | Validation |
|---|---|---|---|---|---|
| promise_ref | FK | Y | promises_to_pay | (promise, payment) | composite key |
| payment_ref | FK | Y | payments (SUCCEEDED, same loan) | | |
| qualifying_amount | DECIMAL(14,2) | Y | INR | | > 0; <= payment amount; sum <= promised_amount |

## Ground truth (separate, `data/generated/ground_truth/` - never input features)
| File | Fields |
|---|---|
| ground_truth.csv / .json | scenario_id, issue_type, rule_id, entity_type, record_ref, related_loan_ref, expected_decision, severity, explanation |
| expected_case_decisions.csv | case_ref, loan_ref, expected_next_action, expected_priority_band, rule_trace |

`audit_events` (database only) records every action taken through the application: event_ref, entity_type, entity_ref, action, actor_ref, reason, occurred_at.
