package com.example.loancollections.model.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CreatePromiseRequest(@NotNull @DecimalMin("0.01") BigDecimal amount, @NotNull LocalDate promiseDate, String contactRef) {}