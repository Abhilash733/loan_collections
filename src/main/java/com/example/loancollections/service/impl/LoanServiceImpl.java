package com.example.loancollections.service.impl;

import com.example.loancollections.exception.BusinessException;
import com.example.loancollections.exception.ResourceNotFoundException;
import com.example.loancollections.model.dto.request.CreateLoanRequest;
import com.example.loancollections.model.entity.Loan;
import com.example.loancollections.repository.LoanRepository;
import com.example.loancollections.repository.jdbc.AccountRepository;
import com.example.loancollections.service.LoanService;
import java.time.Year;
import java.time.ZoneOffset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class LoanServiceImpl implements LoanService {
    private final LoanRepository loanRepository;
    private final AccountRepository accountRepository;

    public LoanServiceImpl(LoanRepository loanRepository, AccountRepository accountRepository) {
        this.loanRepository = loanRepository;
        this.accountRepository = accountRepository;
    }

    @Override
    public Page<Loan> list(Pageable pageable) {
        return loanRepository.findAll(pageable);
    }

    @Override
    public Loan getByRef(String loanRef) {
        return loanRepository.findByLoanRef(loanRef)
                .orElseThrow(() -> new ResourceNotFoundException("Loan not found: " + loanRef));
    }

    @Override
    @Transactional
    public Loan create(CreateLoanRequest request) {
        if (!accountRepository.existsById(request.accountId()))
            throw new BusinessException("Account ID " + request.accountId() + " does not exist. Create an account first.");
        String prefix = "LOAN-" + Year.now(ZoneOffset.UTC) + "-";
        long next = loanRepository.nextSequence(prefix.length() + 1, prefix + "%");
        Loan loan = new Loan(prefix + String.format("%06d", next), request.accountId(), request.principalAmount(),
                request.annualInterestRate(), request.termMonths(), request.startDate(), "ACTIVE");
        return loanRepository.save(loan);
    }
}
