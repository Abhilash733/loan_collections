package com.example.loancollections.controller.api;

import com.example.loancollections.model.dto.response.ApiResponse;
import com.example.loancollections.model.dto.response.CaseAssessment;
import com.example.loancollections.model.dto.response.CollectionCaseResponse;
import com.example.loancollections.service.CollectionCaseService;
import com.example.loancollections.service.WorkQueueService;
import com.example.loancollections.util.DateUtils;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/v1/collection-cases")
public class CollectionCaseApiController {
    private final CollectionCaseService caseService;
    private final WorkQueueService workQueue;

    public CollectionCaseApiController(CollectionCaseService caseService, WorkQueueService workQueue) {
        this.caseService = caseService;
        this.workQueue = workQueue;
    }

    @GetMapping
    public ApiResponse<Page<CollectionCaseResponse>> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(caseService.list(PageRequest.of(page, size, Sort.by("id").ascending())));
    }

    @GetMapping("/{caseRef}")
    public ApiResponse<CollectionCaseResponse> get(@PathVariable String caseRef) {
        return ApiResponse.ok(caseService.getByRef(caseRef));
    }

    @GetMapping("/{caseRef}/assessment")
    public ApiResponse<CaseAssessment> assessment(@PathVariable String caseRef,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResponse.ok(workQueue.assess(caseRef, DateUtils.orToday(asOf)));
    }
}