package com.example.loancollections.service;

import com.example.loancollections.model.dto.response.CollectionCaseResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CollectionCaseService {
    Page<CollectionCaseResponse> list(Pageable pageable);

    CollectionCaseResponse getByRef(String caseRef);
}