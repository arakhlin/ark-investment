package com.ark.arkinvestmentapi.reporting;

import com.ark.arkinvestmentapi.transactions.TransactionType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public class ReportRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ReportRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record TypeAggregate(TransactionType type, BigDecimal openingAmount, BigDecimal amount, long count) {}

    public List<TypeAggregate> aggregate(UUID clientId, UUID fundId, UUID investorId, LocalDate from, LocalDate to) {
        var parameters = new MapSqlParameterSource().addValue("clientId", clientId)
                .addValue("from", from, Types.DATE).addValue("to", to, Types.DATE);
        // Only fixed predicates are composed; every caller-supplied value is bound.
        var scope = new StringBuilder();
        if (fundId != null) {
            scope.append(" and fund_id = :fundId");
            parameters.addValue("fundId", fundId);
        }
        if (investorId != null) {
            scope.append(" and investor_id = :investorId");
            parameters.addValue("investorId", investorId);
        }
        return jdbc.query("""
                select type,
                       coalesce(sum(amount) filter (where transaction_date < :from), 0) as opening,
                       coalesce(sum(amount) filter (where :from is null or transaction_date >= :from), 0) as amount,
                       count(*) filter (where :from is null or transaction_date >= :from) as count
                from transactions
                where client_id = :clientId and (:to is null or transaction_date <= :to)
                """ + scope + " group by type", parameters,
                (rs, row) -> new TypeAggregate(TransactionType.valueOf(rs.getString("type")),
                        rs.getBigDecimal("opening"), rs.getBigDecimal("amount"), rs.getLong("count")));
    }
}
