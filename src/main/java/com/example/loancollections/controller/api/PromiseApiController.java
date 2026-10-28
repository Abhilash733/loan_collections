package com.example.loancollections.controller.api;

import com.example.loancollections.model.dto.request.CreatePromiseRequest;
import com.example.loancollections.model.dto.response.ApiResponse;
import com.example.loancollections.model.dto.response.PromiseResult;
import com.example.loancollections.service.PromiseService;
import com.example.loancollections.util.DateUtils;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PromiseApiController {
    private final PromiseService promiseService;

    public PromiseApiController(PromiseService promiseService) {
        this.promiseService = promiseService;
    }

    @PostMapping("/collection-cases/{caseRef}/promises")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PromiseResult> create(@PathVariable String caseRef, @Valid @RequestBody CreatePromiseRequest request,
                                             Principal principal) {
        return ApiResponse.created(promiseService.create(caseRef, request.amount(), request.promiseDate(),
                request.contactRef(), principal.getName()));
    }

    @PostMapping("/promises/reconcile")
    public ApiResponse<Map<String, Integer>> reconcile(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                                                       Principal principal) {
        return ApiResponse.ok(promiseService.reconcile(DateUtils.orToday(asOf), principal.getName()));
    }
}