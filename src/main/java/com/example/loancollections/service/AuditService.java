package com.example.loancollections.service;

public interface AuditService {
    void record(String entityType, String entityRef, String action, String actor, String reason);
}