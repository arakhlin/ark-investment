package com.ark.arkinvestmentapi.investors;

import jakarta.persistence.*;
import org.hibernate.annotations.PartitionKey;
import org.springframework.data.domain.Persistable;

import java.util.UUID;

@Entity
@Table(name = "investors")
public class Investor implements Persistable<UUID> {
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
    @Column(nullable = false, length = 254)
    private String email;

    protected Investor() {
    }

    public Investor(UUID clientId, String name, String email) {
        this.clientId = clientId;
        this.id = UUID.randomUUID();
        replace(name, email);
    }

    public void replace(String name, String email) {
        this.name = name;
        this.email = email;
    }

    public UUID getClientId() { return clientId; }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

}
