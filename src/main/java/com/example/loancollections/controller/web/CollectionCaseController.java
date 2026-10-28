package com.example.loancollections.controller.web;

import com.example.loancollections.service.CollectionCaseService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/cases")
public class CollectionCaseController {
    private static final int PAGE_SIZE = 100;
    private final CollectionCaseService caseService;

    public CollectionCaseController(CollectionCaseService caseService) {
        this.caseService = caseService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("cases", caseService.list(PageRequest.of(0, PAGE_SIZE, Sort.by("priorityScore").descending())).getContent());
        return "cases/list";
    }
}