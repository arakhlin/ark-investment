package com.ark.arkinvestmentapi.transactions;

import jakarta.persistence.*;
import org.hibernate.annotations.PartitionKey;
import org.springframework.data.domain.Persistable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class Transaction implements Persistable<UUID> {
    @Transient
    private boolean newEntity = true;

    // Assigned UUIDs must INSERT directly instead of merge's ID-only pre-insert lookup.
    @Override
    public boolean isNew() { return newEntity; }

    @PostLoad
    @PostPersist
    private void markPersisted() { newEntity = false; }

    // Include client ownership in Hibernate-generated UPDATE and DELETE predicates.
    @PartitionKey
    @Column(name = "client_id", nullable = false, updatable = false)
    private UUID clientId;

    @Id private UUID id;
    @Column(name = "fund_id", nullable = false, updatable = false) private UUID fundId;
    @Column(name = "investor_id", nullable = false, updatable = false) private UUID investorId;
    @Column(name = "transaction_date", nullable = false) private LocalDate date;
    @Column(nullable = false, precision = 18, scale = 2) private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32) private TransactionType type;
    @Column(length = 1000) private String description;

    protected Transaction() {}

    public Transaction(UUID clientId, UUID fundId, UUID investorId, LocalDate date, BigDecimal amount, TransactionType type, String description) {
        this.clientId = clientId;
        id = UUID.randomUUID();
        this.fundId = fundId;
        this.investorId = investorId;
        replace(date, amount, type, description);
    }

    public void replace(LocalDate date, BigDecimal amount, TransactionType type, String description) {
        this.date = date;
        this.amount = amount;
        this.type = type;
        this.description = description;
    }

    public UUID getClientId() { return clientId; }

    public UUID getId() { return id; }
    public UUID getFundId() { return fundId; }
    public UUID getInvestorId() { return investorId; }
    public LocalDate getDate() { return date; }
    public BigDecimal getAmount() { return amount; }
    public TransactionType getType() { return type; }
    public String getDescription() { return description; }
}
