package com.ark.arkinvestmentapi.transactions;

import com.ark.arkinvestmentapi.security.ClientAccess;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.util.UUID;

/** Insert-only application boundary; JDBC joins the service's JPA datasource transaction. */
@Repository
class TransactionAuditWriter {
    private final ClientAccess access;
    private final NamedParameterJdbcTemplate jdbc;

    TransactionAuditWriter(NamedParameterJdbcTemplate jdbc, ClientAccess access) { this.jdbc = jdbc; this.access = access; }

    record Snapshot(UUID fundId, UUID investorId, LocalDate date, BigDecimal amount,
                    TransactionType type, String description) {
        static Snapshot from(Transaction entity) {
            return new Snapshot(entity.getFundId(), entity.getInvestorId(), entity.getDate(),
                    entity.getAmount(), entity.getType(), entity.getDescription());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID clientId, UUID transactionId, Snapshot previous, Snapshot next) {
        if (previous == null && next == null) throw new IllegalArgumentException("Audit requires a state");
        access.financial(clientId);
        var actor = access.currentUser();
        var parameters = new MapSqlParameterSource()
                .addValue("clientId", clientId).addValue("actorId", actor.id())
                .addValue("actorIssuer", actor.issuer()).addValue("actorSubject", actor.subject())
                .addValue("id", UUID.randomUUID()).addValue("transactionId", transactionId)
                .addValue("operation", previous == null ? "CREATE" : next == null ? "DELETE" : "UPDATE");
        bind(parameters, "previous", previous);
        bind(parameters, "new", next);
        jdbc.update("""
                insert into transaction_audit
                    (client_id, actor_user_id, actor_issuer, actor_subject, id, transaction_id, operation,
                     previous_fund_id, previous_investor_id, previous_date, previous_amount, previous_type, previous_description,
                     new_fund_id, new_investor_id, new_date, new_amount, new_type, new_description)
                values (:clientId, :actorId, :actorIssuer, :actorSubject, :id, :transactionId, :operation,
                        :previousFund, :previousInvestor, :previousDate, :previousAmount, :previousType, :previousDescription,
                        :newFund, :newInvestor, :newDate, :newAmount, :newType, :newDescription)
                """, parameters);
    }

    private static void bind(MapSqlParameterSource parameters, String prefix, Snapshot state) {
        parameters.addValue(prefix + "Fund", state == null ? null : state.fundId(), Types.OTHER)
                .addValue(prefix + "Investor", state == null ? null : state.investorId(), Types.OTHER)
                .addValue(prefix + "Date", state == null ? null : state.date(), Types.DATE)
                .addValue(prefix + "Amount", state == null ? null : state.amount(), Types.NUMERIC)
                .addValue(prefix + "Type", state == null ? null : state.type().name(), Types.VARCHAR)
                .addValue(prefix + "Description", state == null ? null : state.description(), Types.VARCHAR);
    }
}
