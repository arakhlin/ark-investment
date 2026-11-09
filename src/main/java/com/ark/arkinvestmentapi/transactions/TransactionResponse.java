package com.ark.arkinvestmentapi.transactions;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionResponse(UUID id, UUID fundId, UUID investorId, LocalDate date, String amount,
                                  TransactionType type, String description, String currency) {
    public static TransactionResponse from(Transaction value) {
        return new TransactionResponse(value.getId(), value.getFundId(), value.getInvestorId(), value.getDate(),
                value.getAmount().toPlainString(), value.getType(), value.getDescription(), "USD");
    }
}
