package com.example.loancollections.service;

import com.example.loancollections.model.dto.response.EvaluationReport;
import java.time.LocalDate;

/** Scores the application's decisions against the generated ground truth (read from disk, never from the database). */
public interface EvaluationService {
    LocalDate referenceDate();

    EvaluationReport evaluate();
}