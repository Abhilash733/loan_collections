package com.example.loancollections.controller.api;

import com.example.loancollections.model.dto.response.ApiResponse;
import com.example.loancollections.model.dto.response.RuleViolation;
import com.example.loancollections.service.ComplianceService;
import com.example.loancollections.util.DateUtils;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/compliance")
public class ComplianceApiController {
    private final ComplianceService complianceService;

    public ComplianceApiController(ComplianceService complianceService) {
        this.complianceService = complianceService;
    }

    @GetMapping("/exceptions")
    public ApiResponse<List<RuleViolation>> exceptions(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                                                       @RequestParam(required = false) String rule) {
        return ApiResponse.ok(complianceService.detect(DateUtils.orToday(asOf)).stream()
                .filter(v -> rule == null || rule.equals(v.ruleId())).toList());
    }
}