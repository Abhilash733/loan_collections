package com.example.loancollections.service;

public interface CaseLifecycleService {
    /** Applies an allowed status transition and appends it to case history and the audit trail. */
    void transition(long caseId, String caseRef, String from, String to, String reason, String actor);
}