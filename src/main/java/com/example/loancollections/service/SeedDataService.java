package com.example.loancollections.service;

import java.util.Map;

public interface SeedDataService {
    /** Replaces all business data with the generated seed SQL; returns row counts. */
    Map<String, Long> load(String actor);
}