#!/usr/bin/env python3
"""Deterministic synthetic loan-collections dataset generator.

Order of generation:
  1. master data   - customers, accounts, loans, installment schedules
  2. events        - payments + allocations (cash-flow simulation per behaviour profile)
  3. derived       - installment/loan/account status, monthly delinquency snapshots
  4. policy        - contact preferences, permissible windows and restrictions
  5. collections   - day-by-day case simulation: case opening, contact attempts (with
                     restriction enforcement), promises, disputes, status history
  6. anomalies     - a small labelled set of edge cases injected into a valid baseline
  7. labels        - ground truth + expected next action per case (from documented rules)
  8. export        - CSV, JSON, MySQL seed SQL, README, data dictionary, manifest
  9. validation    - tools/validate_dataset.py re-reads the exported CSVs

Uses only the Python standard library and synthetic values; no real personal data.
"""
import argparse, csv, hashlib, heapq, json, math, random, shutil
from datetime import date, datetime, time, timedelta
from decimal import Decimal
from pathlib import Path

import collections_rules as R
import validate_dataset

DATASET_VERSION = "2.0.0"
SEED_DEFAULT = 20261022
REFERENCE_DATE = date(2026, 10, 28)
WINDOW_MONTHS = 12
ANOMALY_RATE = 0.0015  # per anomaly type, relative to the loan count (min 1)

FIELDS = {
    "customers": ["customer_ref", "customer_type", "synthetic_label", "risk_grade"],
    "accounts": ["account_ref", "customer_ref", "status", "opened_at", "closed_at"],
    "loans": ["loan_ref", "account_ref", "principal_amount", "annual_interest_rate", "term_months", "start_date", "status"],
    "installments": ["installment_ref", "loan_ref", "installment_number", "due_date", "principal_due", "interest_due", "fee_due", "status"],
    "payments": ["payment_ref", "loan_ref", "amount", "received_at", "status", "idempotency_key"],
    "payment_allocations": ["allocation_ref", "payment_ref", "installment_ref", "amount", "status"],
    "delinquency_snapshots": ["snapshot_ref", "loan_ref", "as_of_date", "days_past_due", "arrears_amount", "risk_band"],
    "contact_preferences": ["preference_ref", "account_ref", "channel", "effective_from", "effective_to", "permitted_start", "permitted_end", "restriction_type", "timezone_id"],
    "collection_cases": ["case_ref", "loan_ref", "status", "priority_score", "priority_band", "score_breakdown", "reason_codes", "opened_at", "closed_at"],
    "case_status_history": ["event_ref", "case_ref", "previous_status", "new_status", "reason", "actor_ref", "occurred_at"],
    "contact_attempts": ["contact_ref", "case_ref", "channel", "scheduled_at", "attempted_at", "outcome", "blocked_reason", "actor_ref"],
    "promises_to_pay": ["promise_ref", "case_ref", "source_contact_ref", "promised_amount", "promise_date", "created_at", "status", "outcome_reason"],
    "promise_payment_links": ["promise_ref", "payment_ref", "qualifying_amount"],
}
GROUND_TRUTH_FIELDS = ["scenario_id", "issue_type", "rule_id", "entity_type", "record_ref", "related_loan_ref",
                       "expected_decision", "severity", "explanation"]
EXPECTED_FIELDS = ["case_ref", "loan_ref", "expected_next_action", "expected_priority_band", "rule_trace"]

PROFILE_WEIGHTS = {  # behaviour profile probabilities by customer risk grade
    "A": (85, 10, 3, 1, 1), "B": (65, 18, 8, 5, 4), "C": (45, 22, 13, 10, 10), "D": (25, 20, 17, 13, 25)}
PROFILES = ("RELIABLE", "LATE", "PARTIAL", "CURED", "DEFAULTER")
RATE_RANGES = {"A": (9.5, 12.5), "B": (12.5, 16.0), "C": (16.0, 21.0), "D": (21.0, 28.0)}
WINDOWS = [(("09:00", "18:00"), 50), (("10:00", "17:00"), 20), (("08:00", "20:00"), 15),
           (("18:00", "20:00"), 10), (("12:00", "14:00"), 5)]
FIRST = ["Aarav", "Diya", "Kabir", "Anaya", "Ishaan", "Mira", "Rohan", "Tara", "Vihaan", "Zoya", "Arjun", "Nisha"]
LAST = ["Sharma", "Rao", "Patel", "Iyer", "Kumar", "Nair", "Das", "Reddy", "Menon", "Gupta"]
BUSINESS = ["Traders", "Textiles", "Foods", "Logistics", "Hardware", "Pharma"]


def add_months(d, n):
    y, m = divmod(d.month - 1 + n, 12)
    return date(d.year + y, m + 1, min(d.day, 28))


def ts(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def at(d, h=0, m=0, s=0):
    return datetime(d.year, d.month, d.day, h, m, s)


def hhmm(s):
    return time(int(s[:2]), int(s[3:5]))


def month_ends(start, end):
    d = date(start.year, start.month, 1)
    while True:
        nxt = add_months(d, 1)
        me = nxt - timedelta(days=1)
        if me >= end:
            return
        if me >= start:
            yield me
        d = nxt


class Generator:
    def __init__(self, loans_count, seed, ref):
        self.rng = random.Random(seed)
        self.n, self.seed, self.ref = loans_count, seed, ref
        self.window_start = add_months(ref, -WINDOW_MONTHS)
        self.t = {name: [] for name in FIELDS}
        self.ground_truth, self.expected = [], []
        self.customers, self.accounts, self.loans = {}, {}, {}
        self.case_by_ref = {}
        self.pmt_seq = 0

    # ---------------------------------------------------------------- 1. master data
    def build_master(self):
        rng = self.rng
        cust = acct = None
        pending_term = 3
        latest_start = self.ref - timedelta(days=40)
        span = (latest_start - self.window_start).days
        for i in range(1, self.n + 1):
            if acct is None or rng.random() >= 0.08:          # 8% of loans share the previous account
                if cust is None or rng.random() >= 0.15:      # 15% of new accounts belong to an existing customer
                    cust = self.new_customer(len(self.customers) + 1)
                start = self.clamp_day(self.window_start + timedelta(days=rng.randint(0, span)))
                acct = self.new_account(cust, start - timedelta(days=rng.randint(0, 120)))
            else:
                start = self.clamp_day(acct["_opened"] + timedelta(days=rng.randint(0, max(0, (latest_start - acct["_opened"]).days))))
                start = max(start, acct["_first_start"])
            # Terms of 2/3/4 months are paired so the total is exactly 3 installments per loan.
            if i % 2 == 1:
                term = 3 if i == self.n else rng.choice((2, 3, 4))
                pending_term = 6 - term
            else:
                term = pending_term
            self.new_loan(i, acct, cust, start, term)

    @staticmethod
    def clamp_day(d):
        return d - timedelta(days=d.day - 28) if d.day > 28 else d

    def new_customer(self, seq):
        rng = self.rng
        ctype = "BUSINESS" if rng.random() < 0.10 else "INDIVIDUAL"
        label = (f"{rng.choice(LAST)} {rng.choice(BUSINESS)}" if ctype == "BUSINESS"
                 else f"{rng.choice(FIRST)} {rng.choice(LAST)}") + f" (synthetic {seq:06d})"
        row = {"customer_ref": f"CUS-2026-{seq:06d}", "customer_type": ctype, "synthetic_label": label,
               "risk_grade": rng.choices("ABCD", weights=(30, 35, 22, 13))[0]}
        self.t["customers"].append(row)
        self.customers[row["customer_ref"]] = row
        return row

    def new_account(self, cust, opened):
        rng = self.rng
        seq = len(self.accounts) + 1
        ref = f"ACC-2026-{seq:06d}"
        row = {"account_ref": ref, "customer_ref": cust["customer_ref"], "status": "ACTIVE",
               "opened_at": opened.isoformat(), "closed_at": "", "_opened": opened,
               "_first_start": None, "_loans": [], "_prefs": [], "_customer": cust}
        tz = "Asia/Dubai" if rng.random() < 0.10 else "Asia/Kolkata"
        channel = rng.choices(("PHONE", "SMS", "EMAIL", "LETTER"), weights=(45, 20, 30, 5))[0]
        window = rng.choices([w for w, _ in WINDOWS], weights=[wt for _, wt in WINDOWS])[0]
        self.add_pref(row, channel, opened, None, window, "NONE", tz)
        for restriction, prob in (("DO_NOT_CONTACT", 0.025), ("LEGAL_HOLD", 0.008), ("HARDSHIP", 0.05)):
            if rng.random() < prob:
                start = self.window_start + timedelta(days=rng.randint(0, 365))
                end = None if restriction == "DO_NOT_CONTACT" else start + timedelta(
                    days=rng.randint(60, 120) if restriction == "LEGAL_HOLD" else 90)
                self.add_pref(row, None, start, end, None, restriction, tz)
        self.t["accounts"].append(row)
        self.accounts[ref] = row
        return row

    def add_pref(self, acct, channel, start, end, window, restriction, tz):
        pref = {"preference_ref": f"CPF-{acct['account_ref']}-{len(acct['_prefs']) + 1:02d}",
                "account_ref": acct["account_ref"], "channel": channel or "",
                "effective_from": start.isoformat(), "effective_to": end.isoformat() if end else "",
                "permitted_start": f"{window[0]}:00" if window else "", "permitted_end": f"{window[1]}:00" if window else "",
                "restriction_type": restriction, "timezone_id": tz,
                "_from": start, "_to": end, "_window": (hhmm(window[0]), hhmm(window[1])) if window else None}
        acct["_prefs"].append(pref)
        self.t["contact_preferences"].append(pref)
        return pref

    def new_loan(self, i, acct, cust, start, term):
        rng = self.rng
        if acct["_first_start"] is None:
            acct["_first_start"] = start
        lref = f"LOAN-2026-{i:06d}"
        base = rng.lognormvariate(math.log(90000), 0.6) * (2.5 if cust["customer_type"] == "BUSINESS" else 1)
        principal = Decimal(int(min(max(base, 15000), 750000) / 500) * 500).quantize(R.CENT)
        lo, hi = RATE_RANGES[cust["risk_grade"]]
        rate = Decimal(str(round(rng.uniform(lo, hi), 2)))
        monthly = rate / Decimal(1200)
        factor = (1 + monthly) ** term
        emi = R.money(principal * monthly * factor / (factor - 1))
        loan = {"loan_ref": lref, "account_ref": acct["account_ref"], "principal_amount": str(principal),
                "annual_interest_rate": f"{rate:.4f}", "term_months": term, "start_date": start.isoformat(),
                "status": "ACTIVE", "_account": acct, "_customer": cust, "_start": start,
                "_profile": rng.choices(PROFILES, weights=PROFILE_WEIGHTS[cust["risk_grade"]])[0],
                "_installments": [], "_payments": [], "_valid_allocs": []}
        remaining = principal
        for n in range(1, term + 1):
            interest = R.money(remaining * monthly)
            principal_due = remaining if n == term else emi - interest
            remaining -= principal_due
            inst = {"installment_ref": f"INS-{lref}-{n:03d}", "loan_ref": lref, "installment_number": n,
                    "due_date": add_months(start, n).isoformat(), "principal_due": str(principal_due),
                    "interest_due": str(interest), "fee_due": "0.00", "status": "SCHEDULED",
                    "_due": add_months(start, n), "_total": principal_due + interest}
            loan["_installments"].append(inst)
            self.t["installments"].append(inst)
        acct["_loans"].append(loan)
        self.t["loans"].append(loan)
        self.loans[lref] = loan

    # ---------------------------------------------------------------- 2. payments
    def simulate_payments(self, loan):
        rng, ref = self.rng, self.ref
        insts = loan["_installments"]
        profile = loan["_profile"]
        miss = rng.randint(1, max(1, len(insts) - 1))
        heap, order = [], 0

        def push(d, kind, idx, frac=None, retry=0):
            nonlocal order
            order += 1
            heapq.heappush(heap, (d, order, kind, idx, frac, retry))

        for idx, inst in enumerate(insts):
            d = inst["_due"]
            if profile == "RELIABLE":
                push(d + timedelta(days=rng.randint(-3, 2)), "FULL", idx)
            elif profile == "LATE":
                push(d + timedelta(days=rng.randint(5, 45)), "FULL", idx)
            elif profile == "PARTIAL":
                push(d + timedelta(days=rng.randint(0, 15)), "FRACTION", idx, Decimal(str(round(rng.uniform(0.3, 0.8), 2))))
                if rng.random() < 0.45:
                    push(d + timedelta(days=rng.randint(25, 60)), "REST", idx)
            elif profile == "CURED":
                if idx == miss:
                    push(d + timedelta(days=rng.randint(0, 20)), "CATCH_UP", idx)
                elif idx > miss:
                    push(d + timedelta(days=rng.randint(-2, 3)), "FULL", idx)
            elif idx == 0 and rng.random() < 0.5:  # DEFAULTER
                push(d + timedelta(days=rng.randint(0, 10)), "FULL", idx)

        paid = [R.ZERO] * len(insts)
        loan_pmt = 0
        while heap:
            d, _, kind, idx, frac, retry = heapq.heappop(heap)
            if d > ref:
                continue
            remaining = [inst["_total"] - paid[k] for k, inst in enumerate(insts)]
            outstanding = sum(remaining, R.ZERO)
            if kind == "FULL":
                amount = insts[idx]["_total"]
            elif kind == "FRACTION":
                amount = R.money(insts[idx]["_total"] * frac)
            elif kind == "REST":
                amount = remaining[idx]
            else:
                amount = sum((remaining[k] for k, inst in enumerate(insts) if inst["_due"] <= d), R.ZERO)
            amount = min(amount, outstanding)
            if amount <= 0:
                continue
            r = rng.random()
            status = "FAILED" if r < 0.025 else "REVERSED" if r < 0.032 else "SUCCEEDED"
            self.pmt_seq += 1
            loan_pmt += 1
            pref = f"PMT-2026-{self.pmt_seq:06d}"
            received = at(d, rng.randint(3, 13), rng.randint(0, 59), rng.randint(0, 59))
            self.t["payments"].append({"payment_ref": pref, "loan_ref": loan["loan_ref"], "amount": str(amount),
                                       "received_at": ts(received), "status": status,
                                       "idempotency_key": f"IDK-{loan['loan_ref']}-{loan_pmt:03d}"})
            loan["_payments"].append((pref, amount, d, status))
            if status != "FAILED":
                left, seq = amount, 0
                for k, inst in enumerate(insts):
                    if left <= 0:
                        break
                    part = min(left, remaining[k])
                    if part <= 0:
                        continue
                    seq += 1
                    left -= part
                    alloc = {"allocation_ref": f"ALC-{pref}-{seq:02d}", "payment_ref": pref,
                             "installment_ref": inst["installment_ref"], "amount": str(part),
                             "status": "ACTIVE" if status == "SUCCEEDED" else "REVERSED"}
                    self.t["payment_allocations"].append(alloc)
                    if status == "SUCCEEDED":
                        paid[k] += part
                        alloc["_vi"] = len(loan["_valid_allocs"])
                        loan["_valid_allocs"].append((inst["installment_number"], part, d))
            if status != "SUCCEEDED" and retry == 0 and rng.random() < 0.6:
                push(d + timedelta(days=rng.randint(3, 7)), kind, idx, frac, 1)

    def schedule(self, loan):
        return [(i["installment_number"], i["_due"], i["_total"]) for i in loan["_installments"]]

    def position(self, loan, as_of):
        return R.loan_position(self.schedule(loan), loan["_valid_allocs"], as_of)

    # ---------------------------------------------------------------- 3. derived balances
    def derive_balances(self, loan):
        ref = self.ref
        pos = self.position(loan, ref)
        for inst in loan["_installments"]:
            n = inst["installment_number"]
            inst["status"] = R.installment_status(pos.remaining[n], pos.paid[n], inst["_due"], ref)
        loan["status"] = R.loan_status(pos)
        loan["_payoff"] = max((d for _, _, d in loan["_valid_allocs"]), default=None) if pos.paid_off else None
        grade = loan["_customer"]["risk_grade"]
        for d in list(month_ends(loan["_installments"][0]["_due"], ref)) + [ref]:
            p = self.position(loan, d)
            self.t["delinquency_snapshots"].append({
                "snapshot_ref": f"DS-{loan['loan_ref']}-{d:%Y%m%d}", "loan_ref": loan["loan_ref"],
                "as_of_date": d.isoformat(), "days_past_due": p.dpd, "arrears_amount": str(p.arrears),
                "risk_band": R.risk_band(p.dpd, grade)})
            if p.paid_off:
                break

    def close_accounts(self):
        for acct in self.t["accounts"]:
            if all(l["status"] == "PAID_OFF" for l in acct["_loans"]):
                acct["status"] = "CLOSED"
                acct["closed_at"] = max(l["_payoff"] for l in acct["_loans"]).isoformat()

    # ---------------------------------------------------------------- 4/5. collections
    @staticmethod
    def active_restrictions(acct, day):
        return [p for p in acct["_prefs"] if p["restriction_type"] != "NONE"
                and R.restriction_active(p["_from"], p["_to"], day)]

    @staticmethod
    def base_pref(acct, day):
        prefs = [p for p in acct["_prefs"] if p["restriction_type"] == "NONE" and R.restriction_active(p["_from"], p["_to"], day)]
        return prefs[-1] if prefs else None

    def simulate_case(self, loan):
        ref = self.ref
        open_day = None
        for inst in loan["_installments"]:
            candidate = inst["_due"] + timedelta(days=R.CASE_OPEN_DPD)
            if candidate <= ref and self.position(loan, candidate).dpd >= R.CASE_OPEN_DPD:
                open_day = candidate
                break
        if open_day is None:
            return
        rng = self.rng
        acct = loan["_account"]
        case_ref = f"COL-2026-{len(self.t['collection_cases']) + 1:06d}"
        case = {"case_ref": case_ref, "loan_ref": loan["loan_ref"], "status": "", "priority_score": "0.00",
                "priority_band": "NONE", "score_breakdown": "", "reason_codes": "", "opened_at": ts(at(open_day)),
                "closed_at": "", "_loan": loan, "_history": [], "_attempts": [], "_promises": []}
        self.t["collection_cases"].append(case)
        self.case_by_ref[case_ref] = case
        self.transition(case, "OPEN", at(open_day), f"DPD reached {R.CASE_OPEN_DPD}", "COLLECTIONS-ENGINE")

        next_contact = open_day + timedelta(days=1)
        promise = dispute_end = None
        had_dispute = False
        d = open_day
        while d <= ref and case["status"] != "CLOSED":
            pos = self.position(loan, d)
            st = case["status"]
            if st == "DISPUTED":
                if pos.paid_off:
                    self.transition(case, "CLOSED", at(d), "Loan paid off during dispute", "COLLECTIONS-ENGINE")
                    break
                if d == dispute_end:
                    target = "RESOLVED" if pos.arrears == 0 else "IN_PROGRESS"
                    self.transition(case, target, at(d), "Dispute reviewed: debt confirmed", "COMPLIANCE-REVIEW")
                    next_contact = d
            if case["status"] in ("OPEN", "IN_PROGRESS", "AWAITING_PROMISE") and pos.arrears == 0:
                self.transition(case, "RESOLVED", at(d), "Arrears cleared by payment", "COLLECTIONS-ENGINE")
                promise = None
            if case["status"] == "RESOLVED":
                if pos.paid_off:
                    self.transition(case, "CLOSED", at(d), "Loan paid off", "COLLECTIONS-ENGINE")
                    break
                if pos.dpd >= R.CASE_OPEN_DPD:
                    self.transition(case, "IN_PROGRESS", at(d), "Re-delinquent after resolution", "COLLECTIONS-ENGINE")
                    next_contact = d
            if case["status"] == "AWAITING_PROMISE" and d > promise["_date"]:
                self.transition(case, "IN_PROGRESS", at(d), f"Promise {promise['promise_ref']} not kept in full", "COLLECTIONS-ENGINE")
                promise = None
                next_contact = d
            if case["status"] in ("OPEN", "IN_PROGRESS") and d >= next_contact and len(case["_attempts"]) < 8 \
                    and d.weekday() != R.NO_CONTACT_WEEKDAY:
                outcome = self.contact(case, loan, acct, d, pos, had_dispute)
                if outcome == "PROMISE_MADE":
                    promise = case["_promises"][-1]
                    next_contact = promise["_date"] + timedelta(days=1)
                elif outcome == "DISPUTE_RAISED":
                    had_dispute = True
                    dispute_end = d + timedelta(days=rng.randint(25, 45))
                    self.add_pref(acct, None, d + timedelta(days=1), dispute_end - timedelta(days=1), None, "DISPUTE",
                                  self.base_pref(acct, d)["timezone_id"])
                elif outcome == "BLOCKED":
                    next_contact = d + timedelta(days=30)
                else:
                    next_contact = d + timedelta(days=rng.randint(7, 14))
            d += timedelta(days=1)

        for p in case["_promises"]:
            p["status"], links, p["outcome_reason"] = R.promise_outcome(
                p["_amount"], p["_created"].date(), p["_date"], loan["_payments"], ref)
            p["_links"] = links
        self.score_case(case)

    def transition(self, case, new_status, when, reason, actor):
        hist = case["_history"]
        if hist and when <= hist[-1]["_at"]:
            when = hist[-1]["_at"] + timedelta(seconds=1)
        event = {"event_ref": f"CSH-{case['case_ref']}-{len(hist) + 1:03d}", "case_ref": case["case_ref"],
                 "previous_status": case["status"], "new_status": new_status, "reason": reason,
                 "actor_ref": actor, "occurred_at": ts(when), "_at": when}
        hist.append(event)
        self.t["case_status_history"].append(event)
        case["status"] = new_status
        case["closed_at"] = ts(when) if new_status == "CLOSED" else ""
        return event

    def pick_slot(self, pref, day):
        start, end = pref["_window"]
        lo = max(start, R.GLOBAL_CONTACT_WINDOW[0])
        hi = min(end, R.GLOBAL_CONTACT_WINDOW[1])
        minutes = self.rng.randrange(lo.hour * 60 + lo.minute, hi.hour * 60 + hi.minute - 30, 5)
        local = at(day, minutes // 60, minutes % 60)
        return local - R.TZ_OFFSETS[pref["timezone_id"]]

    def contact(self, case, loan, acct, d, pos, had_dispute, forced_outcome=None, actor=None):
        rng = self.rng
        base = self.base_pref(acct, d)
        restrictions = self.active_restrictions(acct, d)
        blocking = [p for p in restrictions if p["restriction_type"] in R.BLOCKING_RESTRICTIONS]
        hardship = any(p["restriction_type"] == "HARDSHIP" for p in restrictions)
        channel = base["channel"]
        if hardship and channel in R.HARDSHIP_BLOCKED_CHANNELS and not forced_outcome:
            channel = "EMAIL"
        scheduled = self.pick_slot(base, d)
        attempt = {"contact_ref": f"CTP-{case['case_ref']}-{len(case['_attempts']) + 1:03d}", "case_ref": case["case_ref"],
                   "channel": channel, "scheduled_at": ts(scheduled), "attempted_at": "", "outcome": "",
                   "blocked_reason": "", "actor_ref": "", "_case": case}
        case["_attempts"].append(attempt)
        self.t["contact_attempts"].append(attempt)
        if blocking and not forced_outcome:
            attempt.update(outcome="BLOCKED", actor_ref="POLICY-ENGINE", blocked_reason="; ".join(
                f"{p['restriction_type']} ({p['preference_ref']})" for p in blocking))
            return "BLOCKED"
        attempted = scheduled + timedelta(minutes=rng.randint(0, 4))
        agent = actor or (f"AGENT-{rng.randint(1, 40):03d}" if channel == "PHONE" else "NOTIFIER-SERVICE")
        attempt.update(attempted_at=ts(attempted), actor_ref=agent)
        if case["status"] == "OPEN":
            self.transition(case, "IN_PROGRESS", attempted + timedelta(minutes=1), "First contact attempt", agent)
        if forced_outcome:
            attempt["outcome"] = forced_outcome
            return forced_outcome
        future = [amt for _, amt, pd, st in loan["_payments"] if st == "SUCCEEDED" and d < pd <= d + timedelta(days=14)]
        x = rng.random()
        outcome = "NO_RESPONSE"
        # Dispute restrictions are account-level, so disputes are only raised on single-loan accounts.
        if not had_dispute and len(acct["_loans"]) == 1 and x < 0.03:
            outcome = "DISPUTE_RAISED"
        elif channel != "LETTER" and x < 0.03 + (0.55 if future else 0.12):
            if future:
                amount = min(pos.arrears, R.money(sum(future, R.ZERO) * rng.choice((Decimal(1), Decimal(1), Decimal("1.25")))))
            else:
                amount = R.money(pos.arrears * rng.choice((Decimal("0.5"), Decimal(1))))
            outcome = "PROMISE_MADE" if amount >= R.CENT else "CONTACTED"
        elif x < 0.6:
            outcome = "CONTACTED"
        attempt["outcome"] = outcome
        if outcome == "PROMISE_MADE":
            promise_date = d + timedelta(days=rng.randint(3, 14))
            p = {"promise_ref": f"PTP-{case['case_ref']}-{len(case['_promises']) + 1:03d}", "case_ref": case["case_ref"],
                 "source_contact_ref": attempt["contact_ref"], "promised_amount": str(amount),
                 "promise_date": promise_date.isoformat(), "created_at": ts(attempted), "status": "PENDING",
                 "outcome_reason": "", "_amount": amount, "_date": promise_date, "_created": attempted, "_links": []}
            case["_promises"].append(p)
            self.t["promises_to_pay"].append(p)
            self.transition(case, "AWAITING_PROMISE", attempted + timedelta(minutes=2), f"Promise {p['promise_ref']} recorded", agent)
        elif outcome == "DISPUTE_RAISED":
            self.transition(case, "DISPUTED", attempted + timedelta(minutes=2), "Customer disputed the debt", agent)
        return outcome

    def case_context(self, case, as_of, ignore=()):
        loan = case["_loan"]
        restrictions = self.active_restrictions(loan["_account"], as_of)
        blocking = [p["restriction_type"] for p in restrictions
                    if p["restriction_type"] in R.BLOCKING_RESTRICTIONS and p["restriction_type"] not in ignore]
        hardship = any(p["restriction_type"] == "HARDSHIP" for p in restrictions)
        broken = sum(1 for p in case["_promises"] if p["status"] in ("MISSED", "PARTIALLY_FULFILLED"))
        pending = any(p["status"] == "PENDING" for p in case["_promises"])
        return self.position(loan, as_of), blocking, hardship, broken, pending

    def score_case(self, case, ignore=()):
        if case["status"] in ("RESOLVED", "CLOSED"):
            case.update(priority_score="0.00", priority_band="NONE", score_breakdown="", reason_codes="CASE_NOT_ACTIVE")
            return
        pos, blocking, hardship, broken, _ = self.case_context(case, self.ref, ignore)
        band = R.risk_band(pos.dpd, case["_loan"]["_customer"]["risk_grade"])
        score, pband, breakdown, reasons = R.priority(pos.dpd, pos.arrears, band, broken, hardship, blocking)
        case.update(priority_score=str(score), priority_band=pband, score_breakdown=breakdown, reason_codes=";".join(reasons))

    # ---------------------------------------------------------------- 6. labelled anomalies
    def inject_anomalies(self):
        rng, ref = self.rng, self.ref
        k = max(1, round(self.n * ANOMALY_RATE))
        used = set()
        counter = [0]

        def pick(candidates, loan_of):
            chosen = []
            pool = [c for c in candidates if loan_of(c) not in used]
            for c in rng.sample(pool, len(pool)):
                if len(chosen) == k:
                    break
                if loan_of(c) not in used:
                    used.add(loan_of(c))
                    chosen.append(c)
            return chosen

        def label(issue, rows, loan_ref, decision, severity, explanation):
            counter[0] += 1
            sid = f"GT-{counter[0]:04d}"
            for rule, entity, record in rows:
                self.ground_truth.append({"scenario_id": sid, "issue_type": issue, "rule_id": rule, "entity_type": entity,
                                          "record_ref": record, "related_loan_ref": loan_ref, "expected_decision": decision,
                                          "severity": severity, "explanation": explanation})

        cases = self.t["collection_cases"]
        # 1. Paid account remains in collection: a closed case of a paid-off loan is reopened.
        for case in pick([c for c in cases if c["status"] == "CLOSED" and c["_loan"]["status"] == "PAID_OFF"],
                         lambda c: c["loan_ref"]):
            ev = self.transition(case, "IN_PROGRESS", max(at(ref, 12), case["_history"][-1]["_at"] + timedelta(seconds=1)),
                                 "Case reopened for collection follow-up", "OPS-USER-017")
            self.score_case(case)
            label("PAID_ACCOUNT_IN_COLLECTION", [("PAID_ACCOUNT_IN_COLLECTION", "COLLECTION_CASE", case["case_ref"]),
                                                 ("INVALID_CASE_TRANSITION", "CASE_STATUS_HISTORY", ev["event_ref"])],
                  case["loan_ref"], "CLOSE_CASE", "HIGH",
                  "Loan has zero outstanding obligations but its case was moved from CLOSED back to IN_PROGRESS.")

        # 2. Contact outside the permitted window: an executed attempt is moved to 06:xx or 21:xx local time.
        attempts = [a for a in self.t["contact_attempts"] if a["outcome"] in ("CONTACTED", "NO_RESPONSE")]
        for a in pick(attempts, lambda a: a["_case"]["loan_ref"]):
            acct = a["_case"]["_loan"]["_account"]
            attempted = datetime.strptime(a["attempted_at"], "%Y-%m-%dT%H:%M:%SZ")
            pref = self.base_pref(acct, attempted.date())
            local = R.local_datetime(attempted, pref["timezone_id"])
            bad = at(local.date(), *rng.choice(((21, rng.randrange(5, 55, 5)), (6, rng.randrange(10, 50, 5)))))
            a["scheduled_at"] = a["attempted_at"] = ts(bad - R.TZ_OFFSETS[pref["timezone_id"]])
            label("CONTACT_OUTSIDE_WINDOW", [("CONTACT_OUTSIDE_WINDOW", "CONTACT_ATTEMPT", a["contact_ref"])],
                  a["_case"]["loan_ref"], "BLOCK_AND_REVIEW", "HIGH",
                  f"Contact executed at {bad:%H:%M} local time, outside {pref['permitted_start']}-{pref['permitted_end']} and/or the 08:00-20:00 global window.")

        # 3. Payment allocated to the wrong installment: a fully paid installment's allocation moves to the last installment.
        inst_by_ref = {i["installment_ref"]: i for i in self.t["installments"]}
        pay_status = {p["payment_ref"]: p["status"] for p in self.t["payments"]}
        pay_date = {p["payment_ref"]: p["received_at"][:10] for p in self.t["payments"]}
        allocs = []
        for a in self.t["payment_allocations"]:
            inst = inst_by_ref[a["installment_ref"]]
            loan = self.loans[inst["loan_ref"]]
            # Only the loan's latest valid allocation is moved, so no later payment is affected by the corruption.
            if a["status"] == "ACTIVE" and pay_status[a["payment_ref"]] == "SUCCEEDED" and inst["status"] == "PAID" \
                    and a["_vi"] == len(loan["_valid_allocs"]) - 1 \
                    and inst["installment_number"] < len(loan["_installments"]) \
                    and loan["_installments"][-1]["due_date"] > pay_date[a["payment_ref"]]:
                allocs.append(a)
        for a in pick(allocs, lambda a: inst_by_ref[a["installment_ref"]]["loan_ref"]):
            inst = inst_by_ref[a["installment_ref"]]
            loan = self.loans[inst["loan_ref"]]
            target = loan["_installments"][-1]
            a["installment_ref"] = target["installment_ref"]
            _, amount, received = loan["_valid_allocs"][a["_vi"]]
            loan["_valid_allocs"][a["_vi"]] = (target["installment_number"], amount, received)
            label("WRONG_INSTALLMENT_ALLOCATION",
                  [("WRONG_INSTALLMENT_ALLOCATION", "PAYMENT_ALLOCATION", a["allocation_ref"]),
                   ("ARREARS_RECONCILIATION", "LOAN", loan["loan_ref"])],
                  loan["loan_ref"], "REALLOCATE_OLDEST_FIRST", "CRITICAL",
                  f"Allocation applied to {target['installment_ref']} although {inst['installment_ref']} was still outstanding; "
                  "stored installment status and snapshots no longer reconcile with allocations.")

        # 4. Promise amount exceeds arrears at the time the promise was taken.
        for p in pick(list(self.t["promises_to_pay"]), lambda p: self.case_by_ref[p["case_ref"]]["loan_ref"]):
            case = self.case_by_ref[p["case_ref"]]
            loan = case["_loan"]
            arrears = self.position(loan, p["_created"].date()).arrears
            p["_amount"] = R.money(arrears * Decimal(str(round(rng.uniform(1.3, 2.0), 2))))
            p["promised_amount"] = str(p["_amount"])
            p["status"], p["_links"], p["outcome_reason"] = R.promise_outcome(
                p["_amount"], p["_created"].date(), p["_date"], loan["_payments"], ref)
            self.score_case(case)
            label("PROMISE_EXCEEDS_ARREARS", [("PROMISE_EXCEEDS_ARREARS", "PROMISE_TO_PAY", p["promise_ref"])],
                  loan["loan_ref"], "REVIEW_PROMISE", "MEDIUM",
                  f"Promised {p['_amount']} while arrears on {p['_created'].date()} were {arrears}.")

        # 5. Disputed debt treated as confirmed: open dispute, case moved back to IN_PROGRESS and contacted.
        disputed = []
        for c in cases:
            if c["status"] != "DISPUTED":
                continue
            acct = c["_loan"]["_account"]
            if any(p["restriction_type"] == "DISPUTE" for p in self.active_restrictions(acct, ref)):
                disputed.append(c)
        for case in pick(disputed, lambda c: c["loan_ref"]):
            loan = case["_loan"]
            acct = loan["_account"]
            last = case["_history"][-1]["_at"].date()
            day = next((ref - timedelta(days=i) for i in range(3) if (ref - timedelta(days=i)).weekday() != R.NO_CONTACT_WEEKDAY
                        and ref - timedelta(days=i) > last), None)
            if day is None:
                used.discard(case["loan_ref"])
                continue
            self.transition(case, "IN_PROGRESS", at(day, 0, 30), "Debt treated as confirmed by collector", "OPS-USER-023")
            attempt_outcome = self.contact(case, loan, acct, day, self.position(loan, day), True,
                                           forced_outcome="CONTACTED", actor="AGENT-099")
            assert attempt_outcome == "CONTACTED"
            attempt = case["_attempts"][-1]
            self.score_case(case, ignore=("DISPUTE",))
            label("DISPUTED_TREATED_AS_CONFIRMED",
                  [("DISPUTED_TREATED_AS_CONFIRMED", "COLLECTION_CASE", case["case_ref"]),
                   ("CONTACT_DURING_RESTRICTION", "CONTACT_ATTEMPT", attempt["contact_ref"])],
                  loan["loan_ref"], "HOLD_DISPUTE", "CRITICAL",
                  "Dispute restriction is still open but the case was moved out of DISPUTED and the customer was contacted.")

    # ---------------------------------------------------------------- 7. expected decisions
    def label_cases(self):
        for case in self.t["collection_cases"]:
            pos, blocking, hardship, broken, pending = self.case_context(case, self.ref)
            action = R.next_action(case["status"], pos, blocking, pending, hardship)
            band = R.risk_band(pos.dpd, case["_loan"]["_customer"]["risk_grade"])
            pband = R.priority(pos.dpd, pos.arrears, band, broken, hardship, blocking)[1]
            self.expected.append({
                "case_ref": case["case_ref"], "loan_ref": case["loan_ref"], "expected_next_action": action,
                "expected_priority_band": R.expected_band(case["status"], action, pband),
                "rule_trace": f"status={case['status']};dpd={pos.dpd};arrears={pos.arrears};paid_off={pos.paid_off};"
                              f"restrictions={'+'.join(sorted(blocking)) or 'NONE'};hardship={hardship};"
                              f"pending_promise={pending};broken_promises={broken}"})

    # ---------------------------------------------------------------- driver
    def run(self):
        self.build_master()
        for loan in self.t["loans"]:
            self.simulate_payments(loan)
            self.derive_balances(loan)
        self.close_accounts()
        for loan in self.t["loans"]:
            self.simulate_case(loan)
        self.inject_anomalies()
        self.t["promise_payment_links"] = [
            {"promise_ref": p["promise_ref"], "payment_ref": ref, "qualifying_amount": str(amount)}
            for p in self.t["promises_to_pay"] for ref, amount in p["_links"]]
        self.t["contact_attempts"].sort(key=lambda r: r["contact_ref"])
        self.t["case_status_history"].sort(key=lambda r: r["event_ref"])
        self.t["contact_preferences"].sort(key=lambda r: r["preference_ref"])
        self.label_cases()


# -------------------------------------------------------------------- export
def clean(rows, fields):
    return [{f: r.get(f, "") for f in fields} for r in rows]


def write_csv(path, fields, rows):
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields, lineterminator="\n")
        w.writeheader()
        w.writerows(clean(rows, fields))


def write_json(path, fields, rows):
    data = [{k: (v if v != "" else None) for k, v in r.items()} for r in clean(rows, fields)]
    path.write_text(json.dumps(data, indent=1, default=str) + "\n", encoding="utf-8")


SQL_COLUMNS = {  # table -> [(db column, csv field or (fk table, csv field))]
    "customers": ["customer_ref", "customer_type", "synthetic_label", "risk_grade"],
    "accounts": ["account_ref", ("customer_id", "customers", "customer_ref"), "status", "opened_at", "closed_at"],
    "loans": ["loan_ref", ("account_id", "accounts", "account_ref"), "principal_amount", "annual_interest_rate",
              "term_months", "start_date", "status"],
    "installments": ["installment_ref", ("loan_id", "loans", "loan_ref"), "installment_number", "due_date",
                     "principal_due", "interest_due", "fee_due", "status"],
    "payments": ["payment_ref", ("loan_id", "loans", "loan_ref"), "amount", "received_at", "status", "idempotency_key"],
    "payment_allocations": ["allocation_ref", ("payment_id", "payments", "payment_ref"),
                            ("installment_id", "installments", "installment_ref"), "amount", "status"],
    "delinquency_snapshots": ["snapshot_ref", ("loan_id", "loans", "loan_ref"), "as_of_date", "days_past_due",
                              "arrears_amount", "risk_band"],
    "contact_preferences": ["preference_ref", ("account_id", "accounts", "account_ref"), "channel", "effective_from",
                            "effective_to", "permitted_start", "permitted_end", "restriction_type", "timezone_id"],
    "collection_cases": ["case_ref", ("loan_id", "loans", "loan_ref"), "status", "priority_score", "priority_band",
                         "score_breakdown", "reason_codes", "opened_at", "closed_at"],
    "case_status_history": ["event_ref", ("case_id", "collection_cases", "case_ref"), "previous_status", "new_status",
                            "reason", "actor_ref", "occurred_at"],
    "contact_attempts": ["contact_ref", ("case_id", "collection_cases", "case_ref"), "channel", "scheduled_at",
                         "attempted_at", "outcome", "blocked_reason", "actor_ref"],
    "promises_to_pay": ["promise_ref", ("case_id", "collection_cases", "case_ref"),
                        ("contact_attempt_id", "contact_attempts", "source_contact_ref"), "promised_amount",
                        "promise_date", "created_at", "status", "outcome_reason"],
    "promise_payment_links": [("promise_id", "promises_to_pay", "promise_ref"), ("payment_id", "payments", "payment_ref"),
                              "qualifying_amount"],
}
KEYS = {"customers": "customer_ref", "accounts": "account_ref", "loans": "loan_ref", "installments": "installment_ref",
        "payments": "payment_ref", "payment_allocations": "allocation_ref", "delinquency_snapshots": "snapshot_ref",
        "contact_preferences": "preference_ref", "collection_cases": "case_ref", "case_status_history": "event_ref",
        "contact_attempts": "contact_ref", "promises_to_pay": "promise_ref"}
NOT_NULL_TEXT = {"score_breakdown", "reason_codes"}  # other empty values are stored as NULL


def sql_value(field, value):
    if value is None or value == "":
        return "''" if field in NOT_NULL_TEXT else "NULL"
    if isinstance(value, int):
        return str(value)
    s = str(value)
    if len(s) == 20 and s[10] == "T" and s.endswith("Z"):
        s = s[:10] + " " + s[11:19]
    return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'"


def write_sql(path, tables):
    ids = {name: {r[key]: i for i, r in enumerate(tables[name], 1)} for name, key in KEYS.items()}
    with path.open("w", encoding="utf-8", newline="\n") as f:
        f.write("-- Synthetic loan-collections seed data (generated; do not edit by hand).\n")
        f.write("-- Replaces all business data. Load with the application admin endpoint or:\n")
        f.write("--   mysql -u <user> -p loan_collections < seed.sql\n")
        f.write("SET NAMES utf8mb4;\nSET time_zone = '+00:00';\nSET FOREIGN_KEY_CHECKS = 0;\n")
        for name in ["audit_events"] + list(SQL_COLUMNS):
            f.write(f"TRUNCATE TABLE {name};\n")
        for name, cols in SQL_COLUMNS.items():
            names = ["id"] if name in KEYS else []
            names += [c if isinstance(c, str) else c[0] for c in cols]
            rows = tables[name]
            for start in range(0, len(rows), 500):
                values = []
                for i, r in enumerate(rows[start:start + 500], start + 1):
                    vals = [str(i)] if name in KEYS else []
                    for c in cols:
                        if isinstance(c, str):
                            vals.append(sql_value(c, r.get(c, "")))
                        else:
                            _, table, field = c
                            vals.append(str(ids[table][r[field]]) if r.get(field) else "NULL")
                    values.append("(" + ",".join(vals) + ")")
                f.write(f"INSERT INTO {name} ({','.join(names)}) VALUES\n" + ",\n".join(values) + ";\n")
        f.write("SET FOREIGN_KEY_CHECKS = 1;\n")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def export(gen, out, command):
    for sub in ("csv", "json", "sql", "ground_truth"):
        shutil.rmtree(out / sub, ignore_errors=True)
        (out / sub).mkdir(parents=True)
    for name, fields in FIELDS.items():
        write_csv(out / "csv" / f"{name}.csv", fields, gen.t[name])
        write_json(out / "json" / f"{name}.json", fields, gen.t[name])
    write_sql(out / "sql" / "seed.sql", {n: clean(gen.t[n], f) for n, f in FIELDS.items()})
    write_csv(out / "ground_truth" / "ground_truth.csv", GROUND_TRUTH_FIELDS, gen.ground_truth)
    write_json(out / "ground_truth" / "ground_truth.json", GROUND_TRUTH_FIELDS, gen.ground_truth)
    write_csv(out / "ground_truth" / "expected_case_decisions.csv", EXPECTED_FIELDS, gen.expected)
    dictionary = Path(__file__).resolve().parent.parent / "docs" / "data-dictionary.md"
    if dictionary.exists():
        shutil.copyfile(dictionary, out / "data_dictionary.md")

    report = validate_dataset.validate(out, gen.ref)
    (out / "validation_report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    files = sorted(p for p in out.rglob("*") if p.is_file() and p.name not in ("manifest.json", "README.md"))
    manifest = {
        "dataset_version": DATASET_VERSION, "seed": gen.seed, "reference_date": gen.ref.isoformat(),
        "observation_window": [gen.window_start.isoformat(), gen.ref.isoformat()],
        "configuration": {"loans": gen.n, "anomaly_rate_per_type": ANOMALY_RATE},
        "generator_command": command,
        "generation_method": "Python random.Random(seed); deterministic iteration order; all balances, statuses, "
                             "scores and labels derived by tools/collections_rules.py",
        "provenance": "Fully synthetic. No external data sources, no real personal or financial data.",
        "external_data_sources": [],
        "row_counts": {n: len(gen.t[n]) for n in FIELDS} | {"ground_truth": len(gen.ground_truth),
                                                            "expected_case_decisions": len(gen.expected)},
        "files": {p.relative_to(out).as_posix(): {"sha256": digest(p), "bytes": p.stat().st_size} for p in files},
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    write_readme(out, manifest, report)
    return manifest, report


def write_readme(out, manifest, report):
    counts = "\n".join(f"| {k} | {v:,} |" for k, v in manifest["row_counts"].items())
    checks = "\n".join(f"| {c['check']} | {'PASS' if c['passed'] else 'FAIL'} | {c['violations']} |"
                       for c in report["structural_checks"])
    rules = "\n".join(f"| {r['rule_id']} | {r['expected']} | {r['detected']} | {r['true_positives']} | "
                      f"{r['false_positives']} | {r['false_negatives']} |" for r in report["anomaly_detection"])
    (out / "README.md").write_text(f"""# Synthetic loan-collections dataset

Generated by `{manifest['generator_command']}`
(seed `{manifest['seed']}`, reference date `{manifest['reference_date']}`,
observation window `{manifest['observation_window'][0]}` to `{manifest['observation_window'][1]}`).
All records are synthetic. See `data_dictionary.md` for every field and `manifest.json` for checksums.

## Layout

| Path | Contents |
|---|---|
| `csv/` | One CSV per entity (input data) |
| `json/` | Same records as JSON arrays (empty values are `null`) |
| `sql/seed.sql` | MySQL seed for the Flyway schema (truncates and reloads business tables) |
| `ground_truth/` | Labels only - **never use as input features**: injected anomalies and expected case decisions |
| `validation_report.json` | Structural checks, anomaly-detection comparison and distribution profile |
| `manifest.json` | Seed, command, row counts, SHA-256 of every file |

## Row counts

| Entity | Rows |
|---|---|
{counts}

## Validation summary - overall {'PASS' if report['overall_passed'] else 'FAIL'}

| Structural check | Result | Violations |
|---|---|---|
{checks}

Injected anomalies vs. independent rule-based detection over the exported CSVs:

| Rule | Labelled | Detected | TP | FP | FN |
|---|---|---|---|---|---|
{rules}
""", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--loans", type=int, default=10000)
    parser.add_argument("--seed", type=int, default=SEED_DEFAULT)
    parser.add_argument("--reference-date", default=REFERENCE_DATE.isoformat())
    parser.add_argument("--out", default="data/generated")
    args = parser.parse_args()
    if args.loans < 2:
        parser.error("--loans must be >= 2")
    gen = Generator(args.loans, args.seed, date.fromisoformat(args.reference_date))
    gen.run()
    command = (f"python tools/generate_synthetic_data.py --loans {args.loans} --seed {args.seed} "
               f"--reference-date {args.reference_date}")
    manifest, report = export(gen, Path(args.out), command)
    print(json.dumps({"output": args.out, "row_counts": manifest["row_counts"],
                      "validation_passed": report["overall_passed"]}, indent=2))
    if not report["overall_passed"]:
        raise SystemExit("Validation failed - see validation_report.json")


if __name__ == "__main__":
    main()
