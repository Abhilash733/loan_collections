package com.example.loancollections.service;

import com.example.loancollections.model.dto.response.PromiseResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public interface PromiseService {
    PromiseResult create(String caseRef, BigDecimal amount, LocalDate promiseDate, String contactRef, String actor);

    /** Settles PENDING promises whose outcome is known at {@code asOf}; returns counts by new status. */
    Map<String, Integer> reconcile(LocalDate asOf, String actor);
}