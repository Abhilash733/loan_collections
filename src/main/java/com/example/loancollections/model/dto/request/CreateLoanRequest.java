package com.example.loancollections.model.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateLoanRequest(
        @NotNull @Min(1) Long accountId,
        @NotNull @DecimalMin("0.01") BigDecimal principalAmount,
        @NotNull @DecimalMin("0.0") BigDecimal annualInterestRate,
        @NotNull @Min(1) Integer termMonths,
        @NotNull LocalDate startDate) {}