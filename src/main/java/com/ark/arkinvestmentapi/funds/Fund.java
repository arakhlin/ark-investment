package com.ark.arkinvestmentapi.funds;

import jakarta.persistence.*;
import org.hibernate.annotations.PartitionKey;
import org.springframework.data.domain.Persistable;

import java.util.UUID;

@Entity
@Table(name = "funds")
public class Fund implements Persistable<UUID> {
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

    @Id
    private UUID id;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(length = 1000)
    private String description;
    @Column(nullable = false, length = 3)
    private String currency = "USD";

    protected Fund() {
    }

    public Fund(UUID clientId, String name, String description) {
        this.clientId = clientId;
        this.id = UUID.randomUUID();
        replace(name, description);
    }

    public void replace(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public UUID getClientId() { return clientId; }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCurrency() {
        return currency;
    }
}
