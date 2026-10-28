package com.example.loancollections.service;

import com.example.loancollections.model.dto.response.MetricsResponse;
import java.time.LocalDate;

public interface MetricsService {
    MetricsResponse compute(LocalDate asOf);
}