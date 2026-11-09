package com.ark.arkinvestmentapi.reporting;

import com.ark.arkinvestmentapi.transactions.TransactionType;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ReportTotalsTests {
    @Test
    void emptyReportHasCanonicalZerosAndEveryBucket() {
        var totals = ReportTotals.from(List.of());
        assertThat(totals.openingBalance()).isEqualTo("0.00");
        assertThat(totals.closingBalance()).isEqualTo("0.00");
        assertThat(totals.transactionCount()).isZero();
        assertThat(totals.byType()).hasSize(5).allSatisfy(b -> {
            assertThat(b.amount()).isEqualTo("0.00");
            assertThat(b.transactionCount()).isZero();
        });
    }

    @Test
    void allSignsAndAmountsBeyondIndividualPrecisionAreExact() {
        var rows = Arrays.stream(TransactionType.values()).map(type ->
                new ReportRepository.TypeAggregate(type, new BigDecimal("0.01"), new BigDecimal("19999999999999999.98"), 2)).toList();
        var totals = ReportTotals.from(rows);
        assertThat(totals.openingBalance()).isEqualTo("-0.01");
        assertThat(totals.totalCredits()).isEqualTo("39999999999999999.96");
        assertThat(totals.totalDebits()).isEqualTo("59999999999999999.94");
        assertThat(totals.netMovement()).isEqualTo("-19999999999999999.98");
        assertThat(totals.closingBalance()).isEqualTo("-19999999999999999.99");
        assertThat(totals.transactionCount()).isEqualTo(10);
    }
}
