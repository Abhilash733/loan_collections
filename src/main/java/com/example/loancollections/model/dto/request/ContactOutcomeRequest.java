package com.example.loancollections.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

public record ContactOutcomeRequest(@NotBlank String outcome, Instant attemptedAt) {}