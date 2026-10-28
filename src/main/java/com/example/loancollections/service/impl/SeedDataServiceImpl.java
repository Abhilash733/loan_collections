package com.example.loancollections.service.impl;

import com.example.loancollections.config.AppProperties;
import com.example.loancollections.exception.BusinessException;
import com.example.loancollections.repository.jdbc.DatasetRepository;
import com.example.loancollections.service.AuditService;
import com.example.loancollections.service.SeedDataService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SeedDataServiceImpl implements SeedDataService {
    private static final Logger log = LoggerFactory.getLogger(SeedDataServiceImpl.class);
    private final Path seedFile;
    private final DatasetRepository dataset;
    private final AuditService audit;

    public SeedDataServiceImpl(AppProperties properties, DatasetRepository dataset, AuditService audit) {
        this.seedFile = properties.datasetPath().resolve("sql").resolve("seed.sql");
        this.dataset = dataset;
        this.audit = audit;
    }

    @Override
    public Map<String, Long> load(String actor) {
        if (!Files.isRegularFile(seedFile))
            throw new BusinessException("Seed file not found: " + seedFile.toAbsolutePath() + "; run tools/generate_synthetic_data.py first");
        log.info("Loading synthetic dataset from {} (requested by {})", seedFile.toAbsolutePath(), actor);
        try {
            dataset.executeScript(seedFile);
        } catch (Exception e) {
            log.error("Seed load failed", e);
            throw new BusinessException("Seed load failed: " + e.getMessage());
        }
        audit.record("DATASET", seedFile.getFileName().toString(), "SEED_LOADED", actor, seedFile.toAbsolutePath().toString());
        return dataset.rowCounts();
    }
}
