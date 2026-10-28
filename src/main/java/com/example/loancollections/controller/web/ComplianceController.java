package com.example.loancollections.controller.web;

import com.example.loancollections.model.dto.response.RuleViolation;
import com.example.loancollections.service.ComplianceService;
import com.example.loancollections.util.DateUtils;
import java.time.LocalDate;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ComplianceController {
    private static final int MAX_ROWS = 500;
    private final ComplianceService complianceService;

    public ComplianceController(ComplianceService complianceService) {
        this.complianceService = complianceService;
    }

    @GetMapping("/exceptions")
    public String exceptions(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                             @RequestParam(required = false) String rule, Model model) {
        LocalDate day = DateUtils.orToday(asOf);
        List<RuleViolation> all = complianceService.detect(day);
        model.addAttribute("asOf", day);
        model.addAttribute("rule", rule);
        model.addAttribute("rules", ComplianceService.RULES);
        model.addAttribute("byRule", new TreeMap<>(all.stream().collect(Collectors.groupingBy(RuleViolation::ruleId, Collectors.counting()))));
        model.addAttribute("rows", all.stream().filter(v -> rule == null || rule.isBlank() || rule.equals(v.ruleId())).limit(MAX_ROWS).toList());
        return "compliance/exceptions";
    }
}