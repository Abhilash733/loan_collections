package com.example.loancollections.repository;

import com.example.loancollections.model.entity.CollectionCase;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionCaseRepository extends JpaRepository<CollectionCase, Long> {
    Optional<CollectionCase> findByCaseRef(String caseRef);
}
