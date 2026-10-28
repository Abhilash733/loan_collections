package com.example.loancollections.controller.api;

import com.example.loancollections.model.dto.response.ApiResponse;
import com.example.loancollections.model.dto.response.CaseAssessment;
import com.example.loancollections.model.dto.response.MetricsResponse;
import com.example.loancollections.service.MetricsService;
import com.example.loancollections.service.WorkQueueService;
import com.example.loancollections.util.DateUtils;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1")
public class WorkQueueApiController {
    private static final int METRICS_QUEUE_PREVIEW = 10;
    private final WorkQueueService workQueue;
    private final MetricsService metrics;

    public WorkQueueApiController(WorkQueueService workQueue, MetricsService metrics) {
        this.workQueue = workQueue;
        this.metrics = metrics;
    }

    @GetMapping("/work-queue")
    public ApiResponse<List<CaseAssessment>> queue(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                                                   @RequestParam(defaultValue = "50") @Min(1) @Max(1000) int limit) {
        return ApiResponse.ok(workQueue.queue(workQueue.assessAll(DateUtils.orToday(asOf))).stream().limit(limit).toList());
    }

    @GetMapping("/metrics")
    public ApiResponse<MetricsResponse> metrics(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResponse.ok(metrics.compute(DateUtils.orToday(asOf)).withQueueLimit(METRICS_QUEUE_PREVIEW));
    }
}