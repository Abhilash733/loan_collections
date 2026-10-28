package com.example.loancollections.controller.api;

import com.example.loancollections.model.dto.response.ApiResponse;
import com.example.loancollections.model.dto.response.EvaluationReport;
import com.example.loancollections.service.EvaluationService;
import com.example.loancollections.service.SeedDataService;
import java.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminApiController {
    private final EvaluationService evaluationService;
    private final SeedDataService seedDataService;

    public AdminApiController(EvaluationService evaluationService, SeedDataService seedDataService) {
        this.evaluationService = evaluationService;
        this.seedDataService = seedDataService;
    }

    @GetMapping("/evaluation")
    public ApiResponse<EvaluationReport> evaluation() {
        return ApiResponse.ok(evaluationService.evaluate());
    }

    @PostMapping("/seed")
    public ApiResponse<Map<String, Long>> seed(Principal principal) {
        return ApiResponse.ok(seedDataService.load(principal.getName()));
    }
}