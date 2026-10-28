package com.example.loancollections.service;

import com.example.loancollections.domain.CollectionsData;
import com.example.loancollections.model.dto.response.RuleViolation;
import java.time.LocalDate;
import java.util.List;

/** Detects records that break collections rules, for review by risk/compliance. */
public interface ComplianceService {
    List<String> RULES = List.of("PAID_ACCOUNT_IN_COLLECTION", "CONTACT_OUTSIDE_WINDOW", "CONTACT_DURING_RESTRICTION",
            "WRONG_INSTALLMENT_ALLOCATION", "ARREARS_RECONCILIATION", "PROMISE_EXCEEDS_ARREARS",
            "DISPUTED_TREATED_AS_CONFIRMED", "INVALID_CASE_TRANSITION");

    List<RuleViolation> detect(LocalDate asOf);

    List<RuleViolation> detect(CollectionsData data, LocalDate asOf);
}