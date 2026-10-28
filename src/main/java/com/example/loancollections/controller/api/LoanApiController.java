package com.example.loancollections.controller.api;

import com.example.loancollections.model.dto.request.CreateLoanRequest;
import com.example.loancollections.model.dto.response.ApiResponse;
import com.example.loancollections.model.dto.response.LoanResponse;
import com.example.loancollections.service.LoanService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/v1/loans")
public class LoanApiController {
    private final LoanService loanService;

    public LoanApiController(LoanService loanService) {
        this.loanService = loanService;
    }

    @GetMapping
    public ApiResponse<Page<LoanResponse>> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(loanService.list(PageRequest.of(page, size, Sort.by("id").ascending())).map(LoanResponse::from));
    }

    @GetMapping("/{loanRef}")
    public ApiResponse<LoanResponse> get(@PathVariable String loanRef) {
        return ApiResponse.ok(LoanResponse.from(loanService.getByRef(loanRef)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LoanResponse> create(@Valid @RequestBody CreateLoanRequest request) {
        return ApiResponse.created(LoanResponse.from(loanService.create(request)));
    }
}