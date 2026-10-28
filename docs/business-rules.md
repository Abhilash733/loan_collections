# Business rules

The rules below are implemented twice and must agree: in Python (`tools/collections_rules.py`, used to derive
every value in the synthetic dataset and its labels) and in Java (`domain/`: `CollectionsRules`, `LoanPosition`,
`PriorityScoring`, `ContactPolicy`, `PromiseOutcome`). The application's Evaluation page measures that
agreement against the generated ground truth.

## Loan schedule
Monthly installments on the same day of month as the start date (day <= 28). Equal-instalment amortisation on the
nominal annual rate / 12, two-decimal half-up rounding; the final installment's principal absorbs rounding residue,
so installment principal always sums to the loan principal.

## Payments and allocation
- Payments are allocated **oldest outstanding installment first** (waterfall); one payment may cover several
  installments and one installment may receive several payments (bridge table).
- Only ACTIVE allocations of SUCCEEDED payments reduce an obligation. FAILED payments have no allocations;
  REVERSED payments keep their allocations with status REVERSED (history is never deleted).
- Exception: money applied to an installment **not yet due** on the payment date while an earlier installment of the
  same loan was still outstanding (`WRONG_INSTALLMENT_ALLOCATION`).

## Arrears, DPD, statuses
At the end of day D: an installment is overdue when `due_date < D`; payments count when received on or before D.
- **Arrears** = sum of unpaid amounts of overdue installments. **DPD** = days from the oldest overdue unpaid
  installment to D, else 0.
- Installment: PAID (nothing remaining) / PARTIALLY_PAID / OVERDUE / SCHEDULED.
- Loan: PAID_OFF (nothing remaining on any installment) / DEFAULTED (DPD >= 90) / ACTIVE.
- Account: CLOSED when all its loans are PAID_OFF.
- Risk band: HIGH if DPD >= 60 or (grade D and DPD >= 30); MEDIUM if DPD >= 30 or (grade C/D and DPD > 0); else LOW.
- Stored statuses and snapshots must reconcile with these derivations (`ARREARS_RECONCILIATION`).

## Prioritisation (explainable)
Score 0-100 = **DPD** (min(DPD,90)/90 x 40) + **balance** (min(arrears,100,000)/100,000 x 30)
+ **risk** (HIGH 20, MEDIUM 10) + **broken promises** (5 each, max 10). Each component is stored in
`score_breakdown`; the score is always the sum of the breakdown.
- **Hardship** caps the score at 50 (`CAP` component) - supportive treatment, never top of the queue.
- **Blocking restriction** (do-not-contact, legal hold, open dispute) gates the score to 0 / INELIGIBLE (`GATE`).
- Bands: HIGH >= 60, MEDIUM >= 30, else LOW; NONE for RESOLVED/CLOSED cases.
- A score never authorises contact; the contact policy below is a separate hard gate.

## Contact policy (fail closed)
A contact is permitted only if all hold, evaluated in the customer's local time zone:
1. A base contact preference is in effect (otherwise blocked: `NO_CONTACT_PREFERENCE`).
2. No DO_NOT_CONTACT, LEGAL_HOLD or DISPUTE restriction is in effect on that local date.
3. During HARDSHIP, PHONE and SMS are not used (EMAIL is substituted).
4. Local time is within the customer's window **and** the global 08:00-20:00 window (end exclusive), not on Sunday.

Blocked decisions are persisted as contact attempts with outcome BLOCKED and the reason. The policy is
re-evaluated when an outcome is recorded. Breaches: `CONTACT_OUTSIDE_WINDOW`, `CONTACT_DURING_RESTRICTION`.

## Case lifecycle
A case opens when DPD reaches 15. Allowed transitions (anything else is `INVALID_CASE_TRANSITION`):

| From | To |
|---|---|
| (new) | OPEN |
| OPEN | IN_PROGRESS, DISPUTED, RESOLVED |
| IN_PROGRESS | AWAITING_PROMISE, DISPUTED, RESOLVED |
| AWAITING_PROMISE | IN_PROGRESS, DISPUTED, RESOLVED |
| DISPUTED | IN_PROGRESS (debt confirmed by review), RESOLVED, CLOSED |
| RESOLVED | IN_PROGRESS (re-delinquent), CLOSED |
| CLOSED | - (terminal) |

Every transition is appended to `case_status_history` (and `audit_events` when made in the application).
- Arrears cleared -> RESOLVED; loan paid off -> CLOSED. A paid-off loan must not stay in an active status
  (`PAID_ACCOUNT_IN_COLLECTION`).
- A raised dispute moves the case to DISPUTED and adds an account-level DISPUTE restriction from the next day.
  While it is open, the case must not be treated as confirmed debt (`DISPUTED_TREATED_AS_CONFIRMED`). In the
  synthetic baseline disputes are only raised on single-loan accounts, so a restriction maps to one case.

## Promise-to-pay
- Allowed only on OPEN/IN_PROGRESS cases with no blocking restriction; amount must be positive and **not exceed
  arrears on the creation date** (`PROMISE_EXCEEDS_ARREARS`); promise date within 30 days.
- Qualifying payments: SUCCEEDED, received between the creation date and the promise date (inclusive), counted up
  to the promised amount and recorded in `promise_payment_links`.
- Outcome: FULFILLED when qualifying >= promised (even early); PENDING until the promise date passes;
  then PARTIALLY_FULFILLED (some qualifying payment) or MISSED (none). Missed and partial promises count as broken.

## Next action (per case, at a date)
In order: loan paid off -> CLOSE_CASE (NO_ACTION if already CLOSED); RESOLVED/CLOSED -> MONITOR if arrears remain,
else NO_ACTION; no arrears -> RESOLVE_CASE; open dispute -> HOLD_DISPUTE; other blocking restriction ->
NO_CONTACT_RESTRICTED; pending promise -> AWAIT_PROMISE; hardship -> CONTACT_HARDSHIP_SUPPORT; otherwise
SCHEDULE_CONTACT.

## Synthetic data generation logic
- Behaviour profile per loan from the customer's risk grade: RELIABLE, LATE, PARTIAL, CURED, DEFAULTER. Payments
  are simulated from these profiles; 2.5% fail and 0.7% are reversed (60% of those are retried).
- Collections are simulated day by day from the case opening date to the reference date: contacts every 7-14
  days (max 8 per case) under the contact policy; outcomes depend on whether the customer actually pays soon
  (customers who will pay are more likely to promise), so promise outcomes follow from real payments.
- Five anomaly types are injected after the baseline is complete (0.15% of loans each, min 1), each labelled with
  the rule(s) it violates in `ground_truth.csv`. Records not labelled must pass every rule.
