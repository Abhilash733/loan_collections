package com.example.loancollections.service;

import com.example.loancollections.domain.CollectionsData;
import com.example.loancollections.model.dto.response.CaseAssessment;
import java.time.LocalDate;
import java.util.List;

/** Prioritises cases: derives balances, applies gates, scores and decides the next action. */
public interface WorkQueueService {
    List<CaseAssessment> assessAll(LocalDate asOf);

    List<CaseAssessment> assessAll(CollectionsData data, LocalDate asOf);

    CaseAssessment assess(String caseRef, LocalDate asOf);

    /** Cases needing attention: contactable cases by score, then holds, restrictions and closures. */
    List<CaseAssessment> queue(List<CaseAssessment> assessments);
}