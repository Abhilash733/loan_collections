package com.example.loancollections.model.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record MetricsResponse(LocalDate asOf, long loans, long activeCases, BigDecimal totalArrears, double averageDpd,
                              Map<String, Long> casesByPriorityBand, Map<String, Long> casesByNextAction,
                              Map<String, Long> contactOutcomes, BigDecimal contactBlockRate,
                              Map<String, Long> promiseOutcomes, BigDecimal promiseKeptRate,
                              Map<String, Long> exceptionsByRule, List<CaseAssessment> queue) {
    public MetricsResponse withQueueLimit(int limit) {
        return new MetricsResponse(asOf, loans, activeCases, totalArrears, averageDpd, casesByPriorityBand, casesByNextAction,
                contactOutcomes, contactBlockRate, promiseOutcomes, promiseKeptRate, exceptionsByRule,
                queue.stream().limit(limit).toList());
    }
}