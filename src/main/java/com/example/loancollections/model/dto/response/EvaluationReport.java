package com.example.loancollections.model.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record EvaluationReport(LocalDate referenceDate, List<RuleScore> rules, int casesLabelled, int casesEvaluated,
                               int actionMatches, int bandMatches, BigDecimal actionAccuracy, BigDecimal bandAccuracy,
                               List<DecisionMismatch> mismatches) {
    public record RuleScore(String ruleId, int labelled, int detected, int truePositives, int falsePositives,
                            int falseNegatives, BigDecimal precision, BigDecimal recall) {}
    public record DecisionMismatch(String caseRef, String expectedAction, String actualAction, String expectedBand, String actualBand) {}
}