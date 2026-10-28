package com.example.loancollections.repository;

import com.example.loancollections.model.entity.Loan;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanRepository extends JpaRepository<Loan, Long> {
    Optional<Loan> findByLoanRef(String loanRef);

    /** Next counter for references of the form {@code <prefix>NNNNNN}, e.g. LOAN-2026-000001. */
    @Query(value = """
            SELECT COALESCE(MAX(CAST(SUBSTRING(loan_ref, :start) AS SIGNED)), 0) + 1
            FROM loans WHERE loan_ref LIKE :pattern""", nativeQuery = true)
    long nextSequence(@Param("start") int start, @Param("pattern") String pattern);
}
