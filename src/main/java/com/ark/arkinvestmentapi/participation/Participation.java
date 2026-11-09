package com.ark.arkinvestmentapi.participation;

import jakarta.persistence.*;
import org.hibernate.annotations.PartitionKey;

import java.util.UUID;

@Entity
@Table(name = "fund_investors")
public class Participation {
    // Include client ownership in Hibernate-generated UPDATE and DELETE predicates.
    @PartitionKey
    @Column(name = "client_id", nullable = false, updatable = false)
    private UUID clientId;

    @Id
    private UUID id;
    @Column(name = "fund_id", nullable = false)
    private UUID fundId;
    @Column(name = "investor_id", nullable = false)
    private UUID investorId;

    protected Participation() {
    }
}
