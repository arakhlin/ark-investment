package com.ark.arkinvestmentapi.transactions;

import java.math.BigDecimal;

public enum TransactionType {
    CONTRIBUTION(true), INTEREST_INCOME(true), DISTRIBUTION(false), GENERAL_EXPENSE(false), MANAGEMENT_FEE(false);

    private final boolean credit;

    TransactionType(boolean credit) { this.credit = credit; }

    public boolean isCredit() { return credit; }

    public BigDecimal signedAmount(BigDecimal magnitude) {
        return credit ? magnitude : magnitude.negate();
    }
}
