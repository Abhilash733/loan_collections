package com.example.loancollections.controller.api;

import com.example.loancollections.model.dto.request.ContactOutcomeRequest;
import com.example.loancollections.model.dto.request.ScheduleContactRequest;
import com.example.loancollections.model.dto.response.ApiResponse;
import com.example.loancollections.model.dto.response.ContactResult;
import com.example.loancollections.service.ContactService;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ContactApiController {
    private final ContactService contactService;

    public ContactApiController(ContactService contactService) {
        this.contactService = contactService;
    }

    @PostMapping("/collection-cases/{caseRef}/contact-attempts")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ContactResult> schedule(@PathVariable String caseRef,
                                               @RequestBody(required = false) ScheduleContactRequest request, Principal principal) {
        return ApiResponse.created(contactService.schedule(caseRef, request == null ? null : request.requestedAt(), principal.getName()));
    }

    @PostMapping("/contact-attempts/{contactRef}/outcome")
    public ApiResponse<ContactResult> recordOutcome(@PathVariable String contactRef,
                                                    @Valid @RequestBody ContactOutcomeRequest request, Principal principal) {
        return ApiResponse.ok(contactService.recordOutcome(contactRef, request.outcome(), request.attemptedAt(), principal.getName()));
    }
}