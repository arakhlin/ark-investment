package com.ark.arkinvestmentapi.transactions;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;

/** Validates original wire values before any conversion or database coercion. */
public final class TransactionValues {
    private TransactionValues() {}

    public static BigDecimal amount(String value) {
        if (value == null || !value.matches("^(?!0\\.00$)(0|[1-9][0-9]{0,15})\\.[0-9]{2}\\z"))
            throw new IllegalArgumentException("Expected positive DECIMAL(18,2) string");
        return new BigDecimal(value);
    }

    public static LocalDate date(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || value.startsWith("0000"))
            throw new IllegalArgumentException("Expected calendar date in years 0001–9999");
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Invalid calendar date", e);
        }
    }

}
