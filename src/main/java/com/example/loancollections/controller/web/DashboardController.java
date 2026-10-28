package com.example.loancollections.controller.web;

import com.example.loancollections.model.dto.response.MetricsResponse;
import com.example.loancollections.service.MetricsService;
import com.example.loancollections.util.DateUtils;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class DashboardController {
    private static final int TOP_QUEUE = 8;
    private final MetricsService metricsService;

    public DashboardController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping({"/", "/dashboard"})
    public String dashboard(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf, Model model) {
        MetricsResponse m = metricsService.compute(DateUtils.orToday(asOf));
        model.addAttribute("m", m);
        model.addAttribute("topQueue", m.queue().stream().limit(TOP_QUEUE).toList());
        model.addAttribute("exceptionTotal", m.exceptionsByRule().values().stream().mapToLong(Long::longValue).sum());
        return "dashboard/index";
    }
}