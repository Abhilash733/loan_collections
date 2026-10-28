package com.example.loancollections.controller.web;

import com.example.loancollections.exception.BusinessException;
import com.example.loancollections.exception.ResourceNotFoundException;
import com.example.loancollections.model.dto.response.CaseAssessment;
import com.example.loancollections.model.dto.response.ContactResult;
import com.example.loancollections.model.dto.response.MetricsResponse;
import com.example.loancollections.service.ContactService;
import com.example.loancollections.service.MetricsService;
import com.example.loancollections.service.PromiseService;
import com.example.loancollections.util.DateUtils;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class WorkQueueController {
    private static final int MAX_ROWS = 200;
    private final MetricsService metricsService;
    private final ContactService contactService;
    private final PromiseService promiseService;

    public WorkQueueController(MetricsService metricsService, ContactService contactService, PromiseService promiseService) {
        this.metricsService = metricsService;
        this.contactService = contactService;
        this.promiseService = promiseService;
    }

    @GetMapping("/queue")
    public String queue(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                        @RequestParam(required = false) String action, Model model) {
        MetricsResponse m = metricsService.compute(DateUtils.orToday(asOf));
        List<CaseAssessment> rows = m.queue().stream()
                .filter(a -> action == null || action.isBlank() || action.equals(a.nextAction())).toList();
        model.addAttribute("m", m);
        model.addAttribute("action", action);
        model.addAttribute("rows", rows.stream().limit(MAX_ROWS).toList());
        model.addAttribute("total", rows.size());
        return "queue/list";
    }

    @PostMapping("/queue/{caseRef}/schedule")
    public String schedule(@PathVariable String caseRef, Principal principal, RedirectAttributes redirect) {
        try {
            ContactResult r = contactService.schedule(caseRef, null, principal.getName());
            if ("BLOCKED".equals(r.outcome()))
                redirect.addFlashAttribute("errorMessage", "Contact for " + caseRef + " blocked: " + String.join("; ", r.reasons()));
            else
                redirect.addFlashAttribute("successMessage", r.contactRef() + " scheduled via " + r.channel() + " at " + r.scheduledAt());
        } catch (BusinessException | ResourceNotFoundException e) {
            redirect.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/queue";
    }

    @PostMapping("/promises/reconcile")
    public String reconcile(Principal principal, RedirectAttributes redirect) {
        Map<String, Integer> counts = promiseService.reconcile(DateUtils.todayUtc(), principal.getName());
        redirect.addFlashAttribute("successMessage", counts.isEmpty()
                ? "No pending promises reached their deadline." : "Promises reconciled: " + counts);
        return "redirect:/queue";
    }
}