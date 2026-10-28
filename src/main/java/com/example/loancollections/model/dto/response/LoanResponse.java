package com.example.loancollections.model.dto.response;

import com.example.loancollections.model.entity.Loan;
import java.math.BigDecimal;
import java.time.LocalDate;

public record LoanResponse(String loanRef, Long accountId, BigDecimal principalAmount,
                           BigDecimal annualInterestRate, Integer termMonths,
                           LocalDate startDate, String status) {
    public static LoanResponse from(Loan loan) {
        return new LoanResponse(loan.getLoanRef(), loan.getAccountId(), loan.getPrincipalAmount(),
                loan.getAnnualInterestRate(), loan.getTermMonths(), loan.getStartDate(), loan.getStatus());
    }
}
