package com.example.loancollections.service.impl;

import com.example.loancollections.exception.ResourceNotFoundException;
import com.example.loancollections.model.dto.response.CollectionCaseResponse;
import com.example.loancollections.repository.CollectionCaseRepository;
import com.example.loancollections.service.CollectionCaseService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CollectionCaseServiceImpl implements CollectionCaseService {
    private final CollectionCaseRepository repository;

    public CollectionCaseServiceImpl(CollectionCaseRepository repository) {
        this.repository = repository;
    }

    @Override
    public Page<CollectionCaseResponse> list(Pageable pageable) {
        return repository.findAll(pageable).map(CollectionCaseResponse::from);
    }

    @Override
    public CollectionCaseResponse getByRef(String caseRef) {
        return repository.findByCaseRef(caseRef).map(CollectionCaseResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Collection case not found: " + caseRef));
    }
}
