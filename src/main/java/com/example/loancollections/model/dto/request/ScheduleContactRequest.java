package com.example.loancollections.model.dto.request;

import java.time.Instant;

/** Optional requested time; when absent the next permitted slot is used. */
public record ScheduleContactRequest(Instant requestedAt) {}