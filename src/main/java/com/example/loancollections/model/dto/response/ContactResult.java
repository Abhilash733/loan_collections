package com.example.loancollections.model.dto.response;

import java.time.Instant;
import java.util.List;

public record ContactResult(String contactRef, String outcome, String channel, Instant scheduledAt, List<String> reasons) {}