package com.example.loancollections.service;

import com.example.loancollections.model.dto.response.ContactResult;
import java.time.Instant;

/** Schedules contact tasks and records outcomes; every decision, including a blocked one, is persisted. */
public interface ContactService {
    /** Schedules at {@code requestedAt}, or at the next permitted slot when null. */
    ContactResult schedule(String caseRef, Instant requestedAt, String actor);

    /** Records the outcome of a scheduled attempt; the contact policy is re-checked at the actual contact time. */
    ContactResult recordOutcome(String contactRef, String outcome, Instant attemptedAt, String actor);
}