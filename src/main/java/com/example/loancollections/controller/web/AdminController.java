package com.example.loancollections.controller.web;

import com.example.loancollections.exception.BusinessException;
import com.example.loancollections.service.EvaluationService;
import com.example.loancollections.service.SeedDataService;
import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminController {
    private final EvaluationService evaluationService;
    private final SeedDataService seedDataService;

    public AdminController(EvaluationService evaluationService, SeedDataService seedDataService) {
        this.evaluationService = evaluationService;
        this.seedDataService = seedDataService;
    }

    @GetMapping("/evaluation")
    public String evaluation(Model model) {
        try {
            model.addAttribute("report", evaluationService.evaluate());
        } catch (BusinessException e) {
            model.addAttribute("errorMessage", e.getMessage());
        }
        return "admin/evaluation";
    }

    @PostMapping("/admin/seed")
    public String seed(Principal principal, RedirectAttributes redirect) {
        try {
            redirect.addFlashAttribute("successMessage", "Synthetic dataset loaded: " + seedDataService.load(principal.getName()));
        } catch (BusinessException e) {
            redirect.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/evaluation";
    }
}