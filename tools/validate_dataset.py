#!/usr/bin/env python3
"""Validate an exported synthetic dataset using only its CSV files.

Structural checks (must have zero violations): primary keys, foreign keys, ID
conventions, enums, date ordering, arithmetic reconciliation, duplicates, joins,
lifecycle consistency, promise reconciliation, priority explainability and
ground-truth separation.

Detection rules (must match ground_truth.csv exactly): the business rules that the
labelled anomalies violate. Each rule is evaluated over every record, so a
detection that is not labelled (false positive) or a label that is not detected
(false negative) fails validation.
"""
import argparse, csv, json, re, statistics
from collections import Counter, defaultdict
from datetime import date, datetime, time
from decimal import Decimal
from pathlib import Path

import collections_rules as R

TABLES = ["customers", "accounts", "loans", "installments", "payments", "payment_allocations",
          "delinquency_snapshots", "contact_preferences", "collection_cases", "case_status_history",
          "contact_attempts", "promises_to_pay", "promise_payment_links"]
KEYS = {"customers": "customer_ref", "accounts": "account_ref", "loans": "loan_ref", "installments": "installment_ref",
        "payments": "payment_ref", "payment_allocations": "allocation_ref", "delinquency_snapshots": "snapshot_ref",
        "contact_preferences": "preference_ref", "collection_cases": "case_ref", "case_status_history": "event_ref",
        "contact_attempts": "contact_ref", "promises_to_pay": "promise_ref"}
FOREIGN_KEYS = [  # child table, field, parent table, optional
    ("accounts", "customer_ref", "customers", False), ("loans", "account_ref", "accounts", False),
    ("installments", "loan_ref", "loans", False), ("payments", "loan_ref", "loans", False),
    ("payment_allocations", "payment_ref", "payments", False),
    ("payment_allocations", "installment_ref", "installments", False),
    ("delinquency_snapshots", "loan_ref", "loans", False), ("contact_preferences", "account_ref", "accounts", False),
    ("collection_cases", "loan_ref", "loans", False), ("case_status_history", "case_ref", "collection_cases", False),
    ("contact_attempts", "case_ref", "collection_cases", False), ("promises_to_pay", "case_ref", "collection_cases", False),
    ("promises_to_pay", "source_contact_ref", "contact_attempts", True),
    ("promise_payment_links", "promise_ref", "promises_to_pay", False),
    ("promise_payment_links", "payment_ref", "payments", False)]
CASE_STATUSES = ("OPEN", "IN_PROGRESS", "AWAITING_PROMISE", "DISPUTED", "RESOLVED", "CLOSED")
ENUMS = {
    ("customers", "customer_type"): ("INDIVIDUAL", "BUSINESS"), ("customers", "risk_grade"): tuple("ABCD"),
    ("accounts", "status"): ("ACTIVE", "CLOSED", "SUSPENDED"),
    ("loans", "status"): ("ACTIVE", "PAID_OFF", "DEFAULTED", "CANCELLED"),
    ("installments", "status"): ("SCHEDULED", "PARTIALLY_PAID", "PAID", "OVERDUE"),
    ("payments", "status"): ("PENDING", "SUCCEEDED", "FAILED", "REVERSED"),
    ("payment_allocations", "status"): ("ACTIVE", "REVERSED"),
    ("delinquency_snapshots", "risk_band"): ("LOW", "MEDIUM", "HIGH"),
    ("contact_preferences", "channel"): ("", "PHONE", "SMS", "EMAIL", "LETTER"),
    ("contact_preferences", "restriction_type"): ("NONE", "DO_NOT_CONTACT", "DISPUTE", "HARDSHIP", "LEGAL_HOLD"),
    ("contact_preferences", "timezone_id"): tuple(R.TZ_OFFSETS),
    ("collection_cases", "status"): CASE_STATUSES,
    ("collection_cases", "priority_band"): ("NONE", "LOW", "MEDIUM", "HIGH", "INELIGIBLE"),
    ("case_status_history", "previous_status"): ("",) + CASE_STATUSES,
    ("case_status_history", "new_status"): CASE_STATUSES,
    ("contact_attempts", "channel"): ("PHONE", "SMS", "EMAIL", "LETTER"),
    ("contact_attempts", "outcome"): ("SCHEDULED", "BLOCKED", "NO_RESPONSE", "CONTACTED", "PROMISE_MADE", "DISPUTE_RAISED"),
    ("promises_to_pay", "status"): ("PENDING", "FULFILLED", "PARTIALLY_FULFILLED", "MISSED", "CANCELLED"),
}
DETECTION_RULES = ["PAID_ACCOUNT_IN_COLLECTION", "CONTACT_OUTSIDE_WINDOW", "CONTACT_DURING_RESTRICTION",
                   "WRONG_INSTALLMENT_ALLOCATION", "ARREARS_RECONCILIATION", "PROMISE_EXCEEDS_ARREARS",
                   "DISPUTED_TREATED_AS_CONFIRMED", "INVALID_CASE_TRANSITION"]


def load(path):
    with path.open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def d(s):
    return date.fromisoformat(s) if s else None


def dt(s):
    return datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ") if s else None


def tm(s):
    return time.fromisoformat(s) if s else None


def D(s):
    return Decimal(s)


class Data:
    def __init__(self, csv_dir, ref):
        self.ref = ref
        self.t = {name: load(csv_dir / f"{name}.csv") for name in TABLES}
        self.by = {name: {r[k]: r for r in self.t[name]} for name, k in KEYS.items()}
        self.inst_by_loan = defaultdict(list)
        for i in self.t["installments"]:
            self.inst_by_loan[i["loan_ref"]].append(i)
        for rows in self.inst_by_loan.values():
            rows.sort(key=lambda r: int(r["installment_number"]))
        self.pay_by_loan = defaultdict(list)
        for p in self.t["payments"]:
            self.pay_by_loan[p["loan_ref"]].append(p)
        self.alloc_by_payment = defaultdict(list)
        self.valid_allocs = defaultdict(list)  # loan -> [(n, amount, received_date, allocation_ref)]
        for a in self.t["payment_allocations"]:
            self.alloc_by_payment[a["payment_ref"]].append(a)
            p, i = self.by["payments"].get(a["payment_ref"]), self.by["installments"].get(a["installment_ref"])
            if p and i and a["status"] == "ACTIVE" and p["status"] == "SUCCEEDED":
                self.valid_allocs[i["loan_ref"]].append((int(i["installment_number"]), D(a["amount"]),
                                                          dt(p["received_at"]).date(), a["allocation_ref"]))
        self.prefs_by_account = defaultdict(list)
        for p in self.t["contact_preferences"]:
            self.prefs_by_account[p["account_ref"]].append(p)
        self.history_by_case = defaultdict(list)
        for h in self.t["case_status_history"]:
            self.history_by_case[h["case_ref"]].append(h)
        self.attempts_by_case = defaultdict(list)
        for a in self.t["contact_attempts"]:
            self.attempts_by_case[a["case_ref"]].append(a)
        self.promises_by_case = defaultdict(list)
        for p in self.t["promises_to_pay"]:
            self.promises_by_case[p["case_ref"]].append(p)
        self.links_by_promise = defaultdict(list)
        for l in self.t["promise_payment_links"]:
            self.links_by_promise[l["promise_ref"]].append(l)

    def schedule(self, loan_ref):
        return [(int(i["installment_number"]), d(i["due_date"]),
                 D(i["principal_due"]) + D(i["interest_due"]) + D(i["fee_due"])) for i in self.inst_by_loan[loan_ref]]

    def position(self, loan_ref, as_of, received_limit=None):
        allocs = [(n, a, r) for n, a, r, _ in self.valid_allocs[loan_ref] if received_limit is None or r <= received_limit]
        return R.loan_position(self.schedule(loan_ref), allocs, as_of)

    def account_of_case(self, case):
        return self.by["loans"][case["loan_ref"]]["account_ref"]

    def restrictions(self, account_ref, day):
        return [p for p in self.prefs_by_account[account_ref] if p["restriction_type"] != "NONE"
                and R.restriction_active(d(p["effective_from"]), d(p["effective_to"]), day)]

    def base_pref(self, account_ref, day):
        prefs = [p for p in self.prefs_by_account[account_ref] if p["restriction_type"] == "NONE"
                 and R.restriction_active(d(p["effective_from"]), d(p["effective_to"]), day)]
        return prefs[-1] if prefs else None

    def payments_tuple(self, loan_ref):
        return [(p["payment_ref"], D(p["amount"]), dt(p["received_at"]).date(), p["status"]) for p in self.pay_by_loan[loan_ref]]


# ------------------------------------------------------------------ structural checks
def structural_checks(x, out):
    results = []

    def check(name, bad):
        bad = list(bad)
        results.append({"check": name, "passed": not bad, "violations": len(bad), "examples": bad[:5]})

    t, by = x.t, x.by
    for name, key in KEYS.items():
        seen = Counter(r[key] for r in t[name])
        check(f"primary_key_unique:{name}", (k for k, c in seen.items() if c > 1 or not k))
    check("primary_key_unique:promise_payment_links",
          (k for k, c in Counter((l["promise_ref"], l["payment_ref"]) for l in t["promise_payment_links"]).items() if c > 1))
    for child, field, parent, optional in FOREIGN_KEYS:
        check(f"foreign_key:{child}.{field}->{parent}",
              (r.get(KEYS.get(child, field)) or r[field] for r in t[child]
               if not (optional and not r[field]) and r[field] not in by[parent]))

    patterns = {"customers": lambda r: re.fullmatch(r"CUS-2026-\d{6}", r["customer_ref"]),
                "accounts": lambda r: re.fullmatch(r"ACC-2026-\d{6}", r["account_ref"]),
                "loans": lambda r: re.fullmatch(r"LOAN-2026-\d{6}", r["loan_ref"]),
                "installments": lambda r: r["installment_ref"] == f"INS-{r['loan_ref']}-{int(r['installment_number']):03d}",
                "payments": lambda r: re.fullmatch(r"PMT-2026-\d{6}", r["payment_ref"]),
                "payment_allocations": lambda r: re.fullmatch(re.escape(f"ALC-{r['payment_ref']}-") + r"\d{2}", r["allocation_ref"]),
                "delinquency_snapshots": lambda r: r["snapshot_ref"] == f"DS-{r['loan_ref']}-{r['as_of_date'].replace('-', '')}",
                "contact_preferences": lambda r: re.fullmatch(re.escape(f"CPF-{r['account_ref']}-") + r"\d{2}", r["preference_ref"]),
                "collection_cases": lambda r: re.fullmatch(r"COL-2026-\d{6}", r["case_ref"]),
                "case_status_history": lambda r: re.fullmatch(re.escape(f"CSH-{r['case_ref']}-") + r"\d{3}", r["event_ref"]),
                "contact_attempts": lambda r: re.fullmatch(re.escape(f"CTP-{r['case_ref']}-") + r"\d{3}", r["contact_ref"]),
                "promises_to_pay": lambda r: re.fullmatch(re.escape(f"PTP-{r['case_ref']}-") + r"\d{3}", r["promise_ref"])}
    for name, ok in patterns.items():
        check(f"id_convention:{name}", (r[KEYS[name]] for r in t[name] if not ok(r)))

    for (name, field), allowed in ENUMS.items():
        check(f"enum:{name}.{field}", (f"{r[KEYS[name]]}={r[field]}" for r in t[name] if r[field] not in allowed))

    # Date ordering
    bad = []
    for a in t["accounts"]:
        if a["closed_at"] and d(a["closed_at"]) < d(a["opened_at"]):
            bad.append(a["account_ref"])
    for l in t["loans"]:
        acct = by["accounts"].get(l["account_ref"])
        insts = x.inst_by_loan[l["loan_ref"]]
        dues = [d(i["due_date"]) for i in insts]
        if acct and d(l["start_date"]) < d(acct["opened_at"]) or dues != sorted(set(dues)) or (dues and dues[0] <= d(l["start_date"])):
            bad.append(l["loan_ref"])
        for p in x.pay_by_loan[l["loan_ref"]]:
            if dt(p["received_at"]).date() < d(l["start_date"]) or dt(p["received_at"]).date() > x.ref:
                bad.append(p["payment_ref"])
    for p in t["contact_preferences"]:
        if p["effective_to"] and d(p["effective_to"]) < d(p["effective_from"]):
            bad.append(p["preference_ref"])
        if p["permitted_start"] and tm(p["permitted_start"]) >= tm(p["permitted_end"]):
            bad.append(p["preference_ref"])
    for c in t["collection_cases"]:
        opened = dt(c["opened_at"])
        insts = x.inst_by_loan[c["loan_ref"]]
        if insts and opened.date() <= d(insts[0]["due_date"]) or (c["closed_at"] and dt(c["closed_at"]) < opened):
            bad.append(c["case_ref"])
        times = [dt(h["occurred_at"]) for h in x.history_by_case[c["case_ref"]]]
        if times != sorted(times) or (times and times[0] < opened):
            bad.append(c["case_ref"])
        for a in x.attempts_by_case[c["case_ref"]]:
            if dt(a["scheduled_at"]).date() < opened.date() or (a["attempted_at"] and dt(a["attempted_at"]) < dt(a["scheduled_at"])):
                bad.append(a["contact_ref"])
    for p in t["promises_to_pay"]:
        if d(p["promise_date"]) < dt(p["created_at"]).date():
            bad.append(p["promise_ref"])
    for s in t["delinquency_snapshots"]:
        if d(s["as_of_date"]) > x.ref:
            bad.append(s["snapshot_ref"])
    check("date_ordering", bad)

    # Arithmetic reconciliation
    bad = []
    for l in t["loans"]:
        insts = x.inst_by_loan[l["loan_ref"]]
        if sum((D(i["principal_due"]) for i in insts), Decimal(0)) != D(l["principal_amount"]) or len(insts) != int(l["term_months"]):
            bad.append(l["loan_ref"])
        if any(D(i[f]) < 0 for i in insts for f in ("principal_due", "interest_due", "fee_due")):
            bad.append(l["loan_ref"])
    for p in t["payments"]:
        allocs = x.alloc_by_payment[p["payment_ref"]]
        total = sum((D(a["amount"]) for a in allocs), Decimal(0))
        expected_status = {"SUCCEEDED": "ACTIVE", "REVERSED": "REVERSED"}.get(p["status"])
        if D(p["amount"]) <= 0 or any(D(a["amount"]) <= 0 for a in allocs):
            bad.append(p["payment_ref"])
        elif p["status"] == "FAILED" and allocs:
            bad.append(p["payment_ref"])
        elif expected_status and (total != D(p["amount"]) or any(a["status"] != expected_status for a in allocs)):
            bad.append(p["payment_ref"])
    for p in t["promises_to_pay"]:
        links = x.links_by_promise[p["promise_ref"]]
        if sum((D(l["qualifying_amount"]) for l in links), Decimal(0)) > D(p["promised_amount"]) or D(p["promised_amount"]) <= 0:
            bad.append(p["promise_ref"])
        for l in links:
            pay = by["payments"].get(l["payment_ref"])
            if pay and (D(l["qualifying_amount"]) <= 0 or D(l["qualifying_amount"]) > D(pay["amount"])):
                bad.append(p["promise_ref"])
    for s in t["delinquency_snapshots"]:
        if int(s["days_past_due"]) < 0 or D(s["arrears_amount"]) < 0:
            bad.append(s["snapshot_ref"])
    check("arithmetic_reconciliation", bad)

    check("duplicate:payments.idempotency_key",
          (k for k, c in Counter(p["idempotency_key"] for p in t["payments"]).items() if c > 1))
    check("duplicate:payments(loan,amount,received_at)",
          (k for k, c in Counter((p["loan_ref"], p["amount"], p["received_at"]) for p in t["payments"]).items() if c > 1))
    check("duplicate:installments(loan,number)",
          (k for k, c in Counter((i["loan_ref"], i["installment_number"]) for i in t["installments"]).items() if c > 1))
    check("duplicate:snapshots(loan,as_of_date)",
          (k for k, c in Counter((s["loan_ref"], s["as_of_date"]) for s in t["delinquency_snapshots"]).items() if c > 1))

    # Join consistency across entities
    bad = []
    for a in t["payment_allocations"]:
        p, i = by["payments"].get(a["payment_ref"]), by["installments"].get(a["installment_ref"])
        if p and i and p["loan_ref"] != i["loan_ref"]:
            bad.append(a["allocation_ref"])
    for p in t["promises_to_pay"]:
        src = by["contact_attempts"].get(p["source_contact_ref"])
        if src and (src["case_ref"] != p["case_ref"] or src["outcome"] != "PROMISE_MADE"):
            bad.append(p["promise_ref"])
        case = by["collection_cases"].get(p["case_ref"])
        for l in x.links_by_promise[p["promise_ref"]]:
            pay = by["payments"].get(l["payment_ref"])
            if case and pay and pay["loan_ref"] != case["loan_ref"]:
                bad.append(p["promise_ref"])
    check("join_consistency", bad)

    # Lifecycle consistency
    bad = []
    for c in t["collection_cases"]:
        hist = x.history_by_case[c["case_ref"]]
        if not hist or hist[0]["previous_status"] or hist[0]["new_status"] != "OPEN" or hist[-1]["new_status"] != c["status"]:
            bad.append(c["case_ref"])
        elif any(h["previous_status"] != prev["new_status"] for prev, h in zip(hist, hist[1:])):
            bad.append(c["case_ref"])
        if bool(c["closed_at"]) != (c["status"] == "CLOSED"):
            bad.append(c["case_ref"])
    loans_by_account = defaultdict(list)
    for l in t["loans"]:
        loans_by_account[l["account_ref"]].append(l)
    for a in t["accounts"]:
        all_paid = all(l["status"] == "PAID_OFF" for l in loans_by_account[a["account_ref"]])
        if (a["status"] == "CLOSED") != all_paid or bool(a["closed_at"]) != (a["status"] == "CLOSED"):
            bad.append(a["account_ref"])
    for a in t["contact_attempts"]:
        if (a["outcome"] == "BLOCKED") != (not a["attempted_at"]) or (a["outcome"] == "BLOCKED") != bool(a["blocked_reason"]):
            bad.append(a["contact_ref"])
    check("lifecycle_consistency", bad)

    # Promise outcomes match subsequent payments
    bad = []
    for p in t["promises_to_pay"]:
        loan_ref = by["collection_cases"][p["case_ref"]]["loan_ref"]
        status, links, _ = R.promise_outcome(D(p["promised_amount"]), dt(p["created_at"]).date(), d(p["promise_date"]),
                                             x.payments_tuple(loan_ref), x.ref)
        stored = sorted((l["payment_ref"], D(l["qualifying_amount"])) for l in x.links_by_promise[p["promise_ref"]])
        if status != p["status"] or sorted(links) != stored:
            bad.append(p["promise_ref"])
    check("promise_outcome_reconciliation", bad)

    # Prioritisation is explainable: score = sum of breakdown components, band follows thresholds
    bad = []
    for c in t["collection_cases"]:
        score = D(c["priority_score"])
        if c["status"] in ("RESOLVED", "CLOSED"):
            if c["priority_band"] != "NONE" or score != 0:
                bad.append(c["case_ref"])
            continue
        parts = dict(p.split("=") for p in c["score_breakdown"].split(";")) if c["score_breakdown"] else {}
        total = sum((D(v) for v in parts.values()), Decimal(0))
        band = "INELIGIBLE" if "GATE" in parts else "HIGH" if score >= 60 else "MEDIUM" if score >= 30 else "LOW"
        if not parts or total != score or band != c["priority_band"] or not c["reason_codes"] or not 0 <= score <= 100:
            bad.append(c["case_ref"])
    check("priority_explainability", bad)

    # Ground truth is separate from input data
    leaks = [p.name for p in (out / "csv").glob("*.csv")
             if p.stem in ("ground_truth", "expected_case_decisions")
             or {"issue_type", "expected_next_action", "expected_decision"} & set(load(p)[0].keys() if load(p) else [])]
    check("ground_truth_separated", leaks)
    return results


# ------------------------------------------------------------------ detection rules
def detect(x):
    found = {rule: set() for rule in DETECTION_RULES}
    t = x.t
    ref = x.ref
    for c in t["collection_cases"]:
        loan_ref, acct = c["loan_ref"], x.account_of_case(c)
        if c["status"] in R.ACTIVE_CASE_STATUSES and x.position(loan_ref, ref).paid_off:
            found["PAID_ACCOUNT_IN_COLLECTION"].add(c["case_ref"])
        if c["status"] in ("OPEN", "IN_PROGRESS", "AWAITING_PROMISE") and any(
                p["restriction_type"] == "DISPUTE" for p in x.restrictions(acct, ref)):
            found["DISPUTED_TREATED_AS_CONFIRMED"].add(c["case_ref"])
        for a in x.attempts_by_case[c["case_ref"]]:
            if not a["attempted_at"]:
                continue
            attempted = dt(a["attempted_at"])
            base = x.base_pref(acct, attempted.date())
            local_day = R.local_datetime(attempted, base["timezone_id"]).date()
            if R.contact_window_violation(attempted, base["timezone_id"], tm(base["permitted_start"]), tm(base["permitted_end"])):
                found["CONTACT_OUTSIDE_WINDOW"].add(a["contact_ref"])
            active = x.restrictions(acct, local_day)
            if any(p["restriction_type"] in R.BLOCKING_RESTRICTIONS for p in active) or (
                    a["channel"] in R.HARDSHIP_BLOCKED_CHANNELS and any(p["restriction_type"] == "HARDSHIP" for p in active)):
                found["CONTACT_DURING_RESTRICTION"].add(a["contact_ref"])
        for p in x.promises_by_case[c["case_ref"]]:
            created = dt(p["created_at"]).date()
            if D(p["promised_amount"]) > x.position(loan_ref, created).arrears:
                found["PROMISE_EXCEEDS_ARREARS"].add(p["promise_ref"])
        prev_ok = R.ALLOWED_TRANSITIONS
        for h in x.history_by_case[c["case_ref"]]:
            if h["new_status"] not in prev_ok.get(h["previous_status"], set()):
                found["INVALID_CASE_TRANSITION"].add(h["event_ref"])

    snapshots_by_loan = defaultdict(list)
    for s in t["delinquency_snapshots"]:
        snapshots_by_loan[s["loan_ref"]].append(s)
    for l in t["loans"]:
        loan_ref = l["loan_ref"]
        grade = x.by["customers"][x.by["accounts"][l["account_ref"]]["customer_ref"]]["risk_grade"]
        pos = x.position(loan_ref, ref)
        mismatch = R.loan_status(pos) != l["status"]
        for (n, due, total), inst in zip(x.schedule(loan_ref), x.inst_by_loan[loan_ref]):
            if pos.paid[n] > total or R.installment_status(pos.remaining[n], pos.paid[n], due, ref) != inst["status"]:
                mismatch = True
        for s in snapshots_by_loan[loan_ref]:
            p = x.position(loan_ref, d(s["as_of_date"]))
            if (p.dpd, p.arrears, R.risk_band(p.dpd, grade)) != (int(s["days_past_due"]), D(s["arrears_amount"]), s["risk_band"]):
                mismatch = True
        if mismatch:
            found["ARREARS_RECONCILIATION"].add(loan_ref)
        # Arrears first: money may not go to a not-yet-due installment while an earlier one is outstanding.
        dues = {n: due for n, due, _ in x.schedule(loan_ref)}
        for n, _, received, alloc_ref in x.valid_allocs[loan_ref]:
            if dues[n] <= received:
                continue
            before = x.position(loan_ref, ref, received_limit=received)
            if any(before.remaining[k] > 0 for k in before.remaining if k < n):
                found["WRONG_INSTALLMENT_ALLOCATION"].add(alloc_ref)
    return found


def profile(x):
    t = x.t
    ref_snaps = [s for s in t["delinquency_snapshots"] if s["as_of_date"] == x.ref.isoformat()]
    buckets = Counter("0" if int(s["days_past_due"]) == 0 else "1-29" if int(s["days_past_due"]) < 30 else
                      "30-59" if int(s["days_past_due"]) < 60 else "60-89" if int(s["days_past_due"]) < 90 else "90+"
                      for s in ref_snaps)
    principals = sorted(D(l["principal_amount"]) for l in t["loans"])
    counts = lambda name, field: dict(sorted(Counter(r[field] or "(none)" for r in t[name]).items()))
    return {
        "customers_by_risk_grade": counts("customers", "risk_grade"),
        "customers_by_type": counts("customers", "customer_type"),
        "loans_by_status": counts("loans", "status"),
        "loans_by_term_months": counts("loans", "term_months"),
        "principal_amount": {"min": str(principals[0]), "median": str(statistics.median(principals)), "max": str(principals[-1])},
        "installments_by_status": counts("installments", "status"),
        "installment_due_date_range": [min(i["due_date"] for i in t["installments"]), max(i["due_date"] for i in t["installments"])],
        "payments_by_status": counts("payments", "status"),
        "dpd_buckets_at_reference_date": dict(sorted(buckets.items())),
        "preferences_by_restriction": counts("contact_preferences", "restriction_type"),
        "cases_by_status": counts("collection_cases", "status"),
        "cases_by_priority_band": counts("collection_cases", "priority_band"),
        "contact_attempts_by_outcome": counts("contact_attempts", "outcome"),
        "contact_attempts_by_channel": counts("contact_attempts", "channel"),
        "promises_by_status": counts("promises_to_pay", "status"),
    }


def validate(out_dir, ref):
    out = Path(out_dir)
    x = Data(out / "csv", ref)
    structural = structural_checks(x, out)
    ground_truth = load(out / "ground_truth" / "ground_truth.csv")
    found = detect(x)
    detection = []
    for rule in DETECTION_RULES:
        expected = {g["record_ref"] for g in ground_truth if g["rule_id"] == rule}
        got = found[rule]
        detection.append({"rule_id": rule, "expected": len(expected), "detected": len(got),
                          "true_positives": len(expected & got), "false_positives": len(got - expected),
                          "false_negatives": len(expected - got), "passed": expected == got,
                          "unexpected_examples": sorted(got - expected)[:5], "missed_examples": sorted(expected - got)[:5]})
    expected_decisions = load(out / "ground_truth" / "expected_case_decisions.csv")
    return {
        "reference_date": ref.isoformat(),
        "overall_passed": all(c["passed"] for c in structural) and all(r["passed"] for r in detection),
        "structural_checks": structural,
        "anomaly_detection": detection,
        "ground_truth_scenarios": dict(sorted(Counter(g["issue_type"] for g in ground_truth
                                                      if g["rule_id"] == g["issue_type"]).items())),
        "expected_next_actions": dict(sorted(Counter(e["expected_next_action"] for e in expected_decisions).items())),
        "distribution_profile": profile(x),
    }


def main():
    parser = argparse.ArgumentParser(description="Validate an exported synthetic dataset")
    parser.add_argument("--dir", default="data/generated")
    parser.add_argument("--reference-date")
    args = parser.parse_args()
    out = Path(args.dir)
    ref = args.reference_date or json.loads((out / "manifest.json").read_text(encoding="utf-8"))["reference_date"]
    report = validate(out, date.fromisoformat(ref))
    print(json.dumps({k: report[k] for k in ("overall_passed", "anomaly_detection")}, indent=2))
    failed = [c for c in report["structural_checks"] if not c["passed"]]
    for c in failed:
        print("FAIL", c["check"], c["examples"])
    raise SystemExit(0 if report["overall_passed"] else 1)


if __name__ == "__main__":
    main()
