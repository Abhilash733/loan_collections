package com.example.loancollections.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Explainable 0-100 priority score from age (DPD), balance (arrears) and risk.
 * DPD up to 40 points (linear to 90 days), arrears up to 30 (linear to 100,000), risk band HIGH 20 / MEDIUM 10,
 * broken promises 5 each up to 10. Hardship caps the score at 50; any blocking restriction gates it to 0/INELIGIBLE.
 */
public final class PriorityScoring {
    private PriorityScoring() {}

    private static final BigDecimal HUNDRED_K = new BigDecimal("100000");
    private static final BigDecimal HARDSHIP_CAP = new BigDecimal("50.00");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public record Score(BigDecimal value, String band, String breakdown, List<String> reasonCodes) {}

    public static Score score(int daysPastDue, BigDecimal arrears, String riskBand, int brokenPromises,
                              boolean hardship, Collection<String> blockingRestrictions) {
        if (daysPastDue < 0 || arrears == null || arrears.signum() < 0 || brokenPromises < 0)
            throw new IllegalArgumentException("DPD, arrears and broken promises must be non-negative");
        Map<String, BigDecimal> parts = new LinkedHashMap<>();
        parts.put("DPD", BigDecimal.valueOf(Math.min(daysPastDue, 90)).multiply(BigDecimal.valueOf(40))
                .divide(BigDecimal.valueOf(90), 2, RoundingMode.HALF_UP));
        parts.put("BALANCE", arrears.min(HUNDRED_K).multiply(BigDecimal.valueOf(30)).divide(HUNDRED_K, 2, RoundingMode.HALF_UP));
        parts.put("RISK", switch (riskBand) {
            case "HIGH" -> new BigDecimal("20.00");
            case "MEDIUM" -> new BigDecimal("10.00");
            default -> ZERO;
        });
        parts.put("PROMISES", BigDecimal.valueOf(Math.min(brokenPromises, 2) * 5L).setScale(2));
        BigDecimal raw = parts.values().stream().reduce(ZERO, BigDecimal::add);

        List<String> reasons = new ArrayList<>();
        reasons.add(daysPastDue >= 90 ? "DPD_90_PLUS" : daysPastDue >= 60 ? "DPD_60_PLUS" : daysPastDue >= 30 ? "DPD_30_PLUS"
                : daysPastDue > 0 ? "DPD_1_29" : "CURRENT");
        if (arrears.compareTo(new BigDecimal("50000")) >= 0) reasons.add("ARREARS_50K_PLUS");
        else if (arrears.signum() > 0) reasons.add("ARREARS_PRESENT");
        if (!"LOW".equals(riskBand)) reasons.add("RISK_" + riskBand);
        if (brokenPromises > 0) reasons.add("BROKEN_PROMISES");

        if (!blockingRestrictions.isEmpty()) {
            parts.put("GATE", ZERO.subtract(raw));
            new TreeSet<>(blockingRestrictions).forEach(r -> reasons.add("RESTRICTED_" + r));
            return new Score(ZERO, "INELIGIBLE", format(parts), List.copyOf(reasons));
        }
        BigDecimal score = raw;
        if (hardship) {
            reasons.add("HARDSHIP");
            if (raw.compareTo(HARDSHIP_CAP) > 0) {
                parts.put("CAP", HARDSHIP_CAP.subtract(raw));
                score = HARDSHIP_CAP;
            }
        }
        String band = score.compareTo(new BigDecimal("60")) >= 0 ? "HIGH"
                : score.compareTo(new BigDecimal("30")) >= 0 ? "MEDIUM" : "LOW";
        return new Score(score, band, format(parts), List.copyOf(reasons));
    }

    private static String format(Map<String, BigDecimal> parts) {
        return parts.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().toPlainString()).collect(Collectors.joining(";"));
    }
}
