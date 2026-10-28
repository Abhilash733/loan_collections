package com.example.loancollections.service;

import com.example.loancollections.model.dto.request.CreateLoanRequest;
import com.example.loancollections.model.entity.Loan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface LoanService {
    Page<Loan> list(Pageable pageable);

    Loan getByRef(String loanRef);

    /** Creates an ACTIVE loan for an existing account with the next LOAN-&lt;year&gt;-NNNNNN reference. */
    Loan create(CreateLoanRequest request);
}