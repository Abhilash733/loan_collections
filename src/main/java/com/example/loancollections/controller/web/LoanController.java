package com.example.loancollections.controller.web;

import com.example.loancollections.exception.BusinessException;
import com.example.loancollections.model.dto.request.CreateLoanRequest;
import com.example.loancollections.model.dto.request.LoanForm;
import com.example.loancollections.model.dto.response.LoanResponse;
import com.example.loancollections.model.entity.Loan;
import com.example.loancollections.service.LoanService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/loans")
public class LoanController {
    private static final int PAGE_SIZE = 100;
    private final LoanService loanService;

    public LoanController(LoanService loanService) {
        this.loanService = loanService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("loans", loanService.list(PageRequest.of(0, PAGE_SIZE, Sort.by("id").descending()))
                .map(LoanResponse::from).getContent());
        return "loans/list";
    }

    @GetMapping("/new")
    public String newLoan(Model model) {
        if (!model.containsAttribute("loanForm")) model.addAttribute("loanForm", new LoanForm());
        return "loans/form";
    }

    @PostMapping("/create")
    public String create(@Valid @ModelAttribute("loanForm") LoanForm form, BindingResult bindingResult,
                         Model model, RedirectAttributes redirect) {
        if (bindingResult.hasErrors()) return "loans/form";
        try {
            Loan created = loanService.create(new CreateLoanRequest(form.getAccountId(), form.getPrincipalAmount(),
                    form.getAnnualInterestRate(), form.getTermMonths(), form.getStartDate()));
            redirect.addFlashAttribute("successMessage", "Loan " + created.getLoanRef() + " created successfully.");
            return "redirect:/loans";
        } catch (BusinessException ex) {
            model.addAttribute("formError", ex.getMessage());
            return "loans/form";
        }
    }
}