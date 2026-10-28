"""Business rules shared by the synthetic data generator and the dataset validator.

Every derived value in the dataset (arrears, days past due, installment/loan status,
risk band, priority score, promise outcome, contact eligibility and next action) is
produced by a function in this module. The rules are documented in
docs/business-rules.md and implemented independently by the Java application, so the
generated ground truth can be used to evaluate it.
"""
from collections import namedtuple
from datetime import time, timedelta
from decimal import Decimal, ROUND_HALF_UP

CENT = Decimal("0.01")
ZERO = Decimal("0.00")

CASE_OPEN_DPD = 15
GLOBAL_CONTACT_WINDOW = (time(8, 0), time(20, 0))
NO_CONTACT_WEEKDAY = 6  # Sunday
TZ_OFFSETS = {"Asia/Kolkata": timedelta(hours=5, minutes=30), "Asia/Dubai": timedelta(hours=4)}

ACTIVE_CASE_STATUSES = ("OPEN", "IN_PROGRESS", "AWAITING_PROMISE", "DISPUTED")
BLOCKING_RESTRICTIONS = ("DO_NOT_CONTACT", "LEGAL_HOLD", "DISPUTE")
HARDSHIP_BLOCKED_CHANNELS = ("PHONE", "SMS")
ALLOWED_TRANSITIONS = {
    "": {"OPEN"},
    "OPEN": {"IN_PROGRESS", "DISPUTED", "RESOLVED"},
    "IN_PROGRESS": {"AWAITING_PROMISE", "DISPUTED", "RESOLVED"},
    "AWAITING_PROMISE": {"IN_PROGRESS", "DISPUTED", "RESOLVED"},
    "DISPUTED": {"IN_PROGRESS", "RESOLVED", "CLOSED"},
    "RESOLVED": {"IN_PROGRESS", "CLOSED"},
    "CLOSED": set(),
}

Position = namedtuple("Position", "arrears dpd remaining paid paid_off")


def money(value):
    return Decimal(value).quantize(CENT, rounding=ROUND_HALF_UP)


def loan_position(schedule, allocations, as_of):
    """Loan balances at the end of day `as_of`.

    schedule: [(installment_number, due_date, total_due)]
    allocations: [(installment_number, amount, received_date)] - only ACTIVE allocations
                 of SUCCEEDED payments (failed/reversed money never reduces an obligation).
    An installment is overdue when due_date < as_of; payments count when received_date <= as_of.
    """
    paid = {n: ZERO for n, _, _ in schedule}
    for n, amount, received in allocations:
        if received <= as_of:
            paid[n] += amount
    remaining = {n: max(ZERO, total - paid[n]) for n, _, total in schedule}
    arrears = sum((remaining[n] for n, due, _ in schedule if due < as_of), ZERO)
    overdue = [due for n, due, _ in schedule if due < as_of and remaining[n] > 0]
    dpd = (as_of - min(overdue)).days if overdue else 0
    return Position(arrears, dpd, remaining, paid, all(v == 0 for v in remaining.values()))


def installment_status(remaining, paid, due, as_of):
    if remaining == 0:
        return "PAID"
    if paid > 0:
        return "PARTIALLY_PAID"
    return "OVERDUE" if due < as_of else "SCHEDULED"


def loan_status(position):
    if position.paid_off:
        return "PAID_OFF"
    return "DEFAULTED" if position.dpd >= 90 else "ACTIVE"


def risk_band(dpd, risk_grade):
    if dpd >= 60 or (risk_grade == "D" and dpd >= 30):
        return "HIGH"
    if dpd >= 30 or (risk_grade in ("C", "D") and dpd > 0):
        return "MEDIUM"
    return "LOW"


def priority(dpd, arrears, band, broken_promises, hardship, blocking):
    """Explainable 0-100 priority score. Returns (score, band, breakdown, reason_codes).

    DPD up to 40 points (linear to 90 days), arrears up to 30 (linear to 100,000),
    risk band HIGH 20 / MEDIUM 10, broken promises 5 each up to 10. Hardship caps the
    score at 50 so supportive cases are never pushed to the top of the queue. Any blocking
    restriction (do-not-contact, legal hold, open dispute) gates the score to 0 / INELIGIBLE.
    """
    parts = [
        ("DPD", (Decimal(min(dpd, 90)) * 40 / Decimal(90)).quantize(CENT, ROUND_HALF_UP)),
        ("BALANCE", (min(arrears, Decimal(100000)) * 30 / Decimal(100000)).quantize(CENT, ROUND_HALF_UP)),
        ("RISK", {"HIGH": Decimal("20.00"), "MEDIUM": Decimal("10.00"), "LOW": ZERO}[band]),
        ("PROMISES", Decimal(min(broken_promises, 2) * 5).quantize(CENT)),
    ]
    raw = sum((v for _, v in parts), ZERO)
    reasons = [("DPD_90_PLUS" if dpd >= 90 else "DPD_60_PLUS" if dpd >= 60 else "DPD_30_PLUS" if dpd >= 30
                else "DPD_1_29" if dpd > 0 else "CURRENT")]
    if arrears >= 50000:
        reasons.append("ARREARS_50K_PLUS")
    elif arrears > 0:
        reasons.append("ARREARS_PRESENT")
    if band != "LOW":
        reasons.append("RISK_" + band)
    if broken_promises:
        reasons.append("BROKEN_PROMISES")
    if blocking:
        parts.append(("GATE", ZERO - raw))
        reasons.extend("RESTRICTED_" + r for r in sorted(set(blocking)))
        return ZERO, "INELIGIBLE", format_breakdown(parts), reasons
    score = raw
    if hardship:
        reasons.append("HARDSHIP")
        if raw > 50:
            parts.append(("CAP", Decimal("50.00") - raw))
            score = Decimal("50.00")
    band_out = "HIGH" if score >= 60 else "MEDIUM" if score >= 30 else "LOW"
    return score, band_out, format_breakdown(parts), reasons


def format_breakdown(parts):
    return ";".join(f"{k}={v}" for k, v in parts)


def promise_outcome(promised, created_date, promise_date, payments, as_of):
    """Reconcile a promise against subsequent payments.

    payments: [(payment_ref, amount, received_date, status)] for the promise's loan.
    Qualifying payments are SUCCEEDED and received between the promise creation date and
    the promise date (inclusive). Returns (status, links, reason) where links are
    [(payment_ref, qualifying_amount)] capped cumulatively at the promised amount.
    """
    end = min(promise_date, as_of)
    qualifying = sorted((p for p in payments if p[3] == "SUCCEEDED" and created_date <= p[2] <= end),
                        key=lambda p: (p[2], p[0]))
    links, total = [], ZERO
    for ref, amount, _, _ in qualifying:
        if total >= promised:
            break
        part = min(amount, promised - total)
        links.append((ref, part))
        total += part
    if total >= promised:
        status = "FULFILLED"
    elif as_of <= promise_date:
        status = "PENDING"
    elif total > 0:
        status = "PARTIALLY_FULFILLED"
    else:
        status = "MISSED"
    return status, links, f"Qualifying payments {total} of {promised} by {promise_date.isoformat()}"


def local_datetime(utc_naive, tz):
    return utc_naive + TZ_OFFSETS[tz]


def contact_window_violation(utc_naive, tz, permitted_start, permitted_end):
    """Return a reason string when a contact time is not permissible, else None."""
    local = local_datetime(utc_naive, tz)
    if local.weekday() == NO_CONTACT_WEEKDAY:
        return "SUNDAY"
    t = local.time()
    if not (GLOBAL_CONTACT_WINDOW[0] <= t < GLOBAL_CONTACT_WINDOW[1]):
        return "OUTSIDE_GLOBAL_WINDOW"
    if not (permitted_start <= t < permitted_end):
        return "OUTSIDE_CUSTOMER_WINDOW"
    return None


def restriction_active(effective_from, effective_to, day):
    return effective_from <= day and (effective_to is None or day <= effective_to)


def next_action(case_status, position, blocking, pending_promise, hardship):
    """Expected next operational action for a case at the reference date."""
    if position.paid_off:
        return "NO_ACTION" if case_status == "CLOSED" else "CLOSE_CASE"
    if case_status in ("RESOLVED", "CLOSED"):
        return "MONITOR" if position.arrears > 0 else "NO_ACTION"
    if position.arrears == 0:
        return "RESOLVE_CASE"
    if "DISPUTE" in blocking:
        return "HOLD_DISPUTE"
    if blocking:
        return "NO_CONTACT_RESTRICTED"
    if pending_promise:
        return "AWAIT_PROMISE"
    if hardship:
        return "CONTACT_HARDSHIP_SUPPORT"
    return "SCHEDULE_CONTACT"


def expected_band(case_status, action, priority_band):
    return "NONE" if action == "CLOSE_CASE" or case_status in ("RESOLVED", "CLOSED") else priority_band
