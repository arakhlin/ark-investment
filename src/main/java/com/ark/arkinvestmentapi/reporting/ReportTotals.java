package com.ark.arkinvestmentapi.reporting;

import com.ark.arkinvestmentapi.transactions.TransactionType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public record ReportTotals(String openingBalance, String totalCredits, String totalDebits,
                           String netMovement, String closingBalance, long transactionCount, List<TypeTotal> byType) {
    public record TypeTotal(TransactionType type, String amount, long transactionCount) {}

    public static ReportTotals from(List<ReportRepository.TypeAggregate> aggregates) {
        var rows = new EnumMap<TransactionType, ReportRepository.TypeAggregate>(TransactionType.class);
        aggregates.forEach(row -> rows.put(row.type(), row));
        BigDecimal opening = BigDecimal.ZERO, credits = BigDecimal.ZERO, debits = BigDecimal.ZERO;
        long count = 0;
        var buckets = new ArrayList<TypeTotal>();
        for (var type : TransactionType.values()) {
            var row = rows.getOrDefault(type, new ReportRepository.TypeAggregate(type, BigDecimal.ZERO, BigDecimal.ZERO, 0));
            opening = opening.add(type.signedAmount(row.openingAmount()));
            if (type.isCredit()) credits = credits.add(row.amount());
            else debits = debits.add(row.amount());
            count += row.count();
            buckets.add(new TypeTotal(type, money(row.amount()), row.count()));
        }
        var movement = credits.subtract(debits);
        return new ReportTotals(money(opening), money(credits), money(debits), money(movement),
                money(opening.add(movement)), count, List.copyOf(buckets));
    }

    private static String money(BigDecimal value) { return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString(); }
}
