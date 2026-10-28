package com.example.loancollections.service.impl;

import com.example.loancollections.config.AppProperties;
import com.example.loancollections.domain.CollectionsData;
import com.example.loancollections.exception.BusinessException;
import com.example.loancollections.model.dto.response.CaseAssessment;
import com.example.loancollections.model.dto.response.EvaluationReport;
import com.example.loancollections.model.dto.response.EvaluationReport.DecisionMismatch;
import com.example.loancollections.model.dto.response.EvaluationReport.RuleScore;
import com.example.loancollections.model.dto.response.RuleViolation;
import com.example.loancollections.repository.jdbc.CollectionsDataRepository;
import com.example.loancollections.service.ComplianceService;
import com.example.loancollections.service.EvaluationService;
import com.example.loancollections.service.WorkQueueService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Labels are read from the dataset directory at evaluation time only; they are never loaded into the database
 * or used as inputs to any decision.
 */
@Service
@Transactional(readOnly = true)
public class EvaluationServiceImpl implements EvaluationService {
    private static final int MAX_MISMATCHES = 25;
    private final Path datasetDir;
    private final CollectionsDataRepository collectionsData;
    private final ComplianceService compliance;
    private final WorkQueueService workQueue;
    private final ObjectMapper mapper;

    public EvaluationServiceImpl(AppProperties properties, CollectionsDataRepository collectionsData,
                                 ComplianceService compliance, WorkQueueService workQueue, ObjectMapper mapper) {
        this.datasetDir = properties.datasetPath();
        this.collectionsData = collectionsData;
        this.compliance = compliance;
        this.workQueue = workQueue;
        this.mapper = mapper;
    }

    @Override
    public LocalDate referenceDate() {
        Path manifest = datasetDir.resolve("manifest.json");
        try {
            JsonNode date = mapper.readTree(manifest.toFile()).get("reference_date");
            if (date == null) throw new BusinessException("reference_date missing in " + manifest.toAbsolutePath());
            return LocalDate.parse(date.asText());
        } catch (IOException e) {
            throw new BusinessException("Dataset manifest not found in " + datasetDir.toAbsolutePath()
                    + "; run tools/generate_synthetic_data.py first");
        }
    }

    @Override
    public EvaluationReport evaluate() {
        LocalDate ref = referenceDate();
        List<Map<String, String>> groundTruth = readCsv(datasetDir.resolve("ground_truth/ground_truth.csv"));
        Map<String, Map<String, String>> expected = readCsv(datasetDir.resolve("ground_truth/expected_case_decisions.csv"))
                .stream().collect(Collectors.toMap(r -> r.get("case_ref"), Function.identity()));

        CollectionsData data = collectionsData.load();
        Map<String, Set<String>> found = new HashMap<>();
        for (RuleViolation v : compliance.detect(data, ref))
            found.computeIfAbsent(v.ruleId(), k -> new HashSet<>()).add(v.recordRef());
        List<RuleScore> rules = new ArrayList<>();
        for (String rule : ComplianceService.RULES) {
            Set<String> labelled = groundTruth.stream().filter(g -> rule.equals(g.get("rule_id")))
                    .map(g -> g.get("record_ref")).collect(Collectors.toSet());
            Set<String> got = found.getOrDefault(rule, Set.of());
            int tp = (int) got.stream().filter(labelled::contains).count();
            rules.add(new RuleScore(rule, labelled.size(), got.size(), tp, got.size() - tp, labelled.size() - tp,
                    ratio(tp, got.size()), ratio(tp, labelled.size())));
        }

        int evaluated = 0, actionOk = 0, bandOk = 0;
        List<DecisionMismatch> mismatches = new ArrayList<>();
        for (CaseAssessment a : workQueue.assessAll(data, ref)) {
            Map<String, String> label = expected.get(a.caseRef());
            if (label == null) continue;
            evaluated++;
            boolean actionMatch = label.get("expected_next_action").equals(a.nextAction());
            boolean bandMatch = label.get("expected_priority_band").equals(a.expectedBand());
            if (actionMatch) actionOk++;
            if (bandMatch) bandOk++;
            if ((!actionMatch || !bandMatch) && mismatches.size() < MAX_MISMATCHES)
                mismatches.add(new DecisionMismatch(a.caseRef(), label.get("expected_next_action"), a.nextAction(),
                        label.get("expected_priority_band"), a.expectedBand()));
        }
        return new EvaluationReport(ref, rules, expected.size(), evaluated, actionOk, bandOk,
                ratio(actionOk, evaluated), ratio(bandOk, evaluated), mismatches);
    }

    private static BigDecimal ratio(int numerator, int denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private static List<Map<String, String>> readCsv(Path path) {
        try (Reader reader = Files.newBufferedReader(path)) {
            return CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)
                    .stream().map(CSVRecord::toMap).toList();
        } catch (IOException e) {
            throw new BusinessException("Cannot read " + path.toAbsolutePath() + ": " + e.getMessage());
        }
    }
}
