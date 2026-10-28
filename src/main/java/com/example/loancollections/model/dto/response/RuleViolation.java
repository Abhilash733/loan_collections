package com.example.loancollections.model.dto.response;

/** A record that breaks a collections rule, for compliance review. */
public record RuleViolation(String ruleId, String severity, String entityType, String recordRef, String loanRef, String detail) {}