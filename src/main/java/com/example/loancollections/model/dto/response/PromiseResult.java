package com.example.loancollections.model.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PromiseResult(String promiseRef, BigDecimal promisedAmount, LocalDate promiseDate, BigDecimal arrearsAtCreation) {}