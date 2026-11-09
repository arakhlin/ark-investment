# Architecture

## Overview

Ark Investment API is a Java 21 / Spring Boot application built as a **Modular Monolith**. It manages Clients, Funds, Investors, Participations, Transactions, and Financial Reports.

The application uses PostgreSQL for persistence, Keycloak for local authentication, and Spring Security for authorization.

The architecture prioritizes financial correctness, clear domain boundaries, and straightforward deployment over distributed-system complexity.

## 1. Modular Monolith

The application is organized into domain-oriented packages within a single deployable service and a shared PostgreSQL database.

This approach was chosen because financial operations depend on closely related data. Transactions must reference valid Fund–Investor Participations, and reports aggregate transactions across those relationships.

A single application and database allow these operations to use local ACID transactions and relational constraints.

**Trade-off:** Modules share deployment, scaling, and database resources. Package boundaries are not enforced as independent modules. Microservices would be worth reconsidering if independent scaling or deployment becomes a demonstrated requirement.

## 2. Data Model and Financial Integrity

Funds and Investors have a many-to-many relationship through Participation. Every Transaction belongs to one Client, Fund, and Investor, and requires an existing Participation.

PostgreSQL composite foreign keys prevent invalid Fund–Investor relationships and cross-Client references. Restrictive deletion rules prevent removing referenced records.

Financial amounts use `BigDecimal` in Java and `NUMERIC(18,2)` in PostgreSQL. Transactions store positive amounts, with credit or debit direction determined by the transaction type.

The five supported types are Contribution, Interest Income, Distribution, General Expense, and Management Fee.

**Trade-off:** Referential integrity and exact arithmetic add validation and schema constraints, but avoid relying solely on application-level checks.

## 3. Transactions and Concurrency

Business writes use Spring `@Transactional` service boundaries.

Participation changes and Transaction creation acquire pessimistic locks in a consistent Fund-then-Investor order. Database constraints provide an additional safeguard against concurrent changes.

Transaction updates and deletions lock the existing Transaction row.

**Trade-off:** Pessimistic locking simplifies consistency guarantees but can reduce concurrency when operations share the same Fund or Investor. The API does not implement optimistic versioning or idempotency keys; concurrent updates follow last-write-wins semantics, and repeated POST requests may create duplicates.

## 4. Financial Reporting

Fund and Investor reports calculate balances from persisted Transactions rather than maintaining mutable balance columns.

Reporting uses PostgreSQL aggregation and a read-only `REPEATABLE_READ` transaction to provide a consistent snapshot during each request, even when concurrent writes occur.

Reports include opening balance, credits, debits, net movement, closing balance, and totals by transaction type.

**Trade-off:** Reports remain consistent within a request, but aggregating historical transactions has a database cost. Corrections and deletions change subsequent historical report results; reports are not immutable accounting snapshots.

## 5. Authentication, Authorization, and Client Isolation

Spring Security validates JWTs issued by the configured identity provider. Keycloak is used for the local demo.

Application users, roles, and Client ownership are stored in PostgreSQL rather than trusted from JWT role claims.

The API supports six roles covering Client administration, financial operations, and reporting. Client-specific users can access only their own Client's permitted resources, while ARK roles provide explicitly defined cross-Client capabilities.

Authorization is enforced in service policies and Client-scoped queries, supported by database constraints.

**Trade-off:** Each application user has one database-managed role; changes take effect on subsequent authorization checks. Identity binding requires trusted operator approval, which does not continuously synchronize identity-provider account status. Shared-schema isolation has no PostgreSQL Row-Level Security. The local demo uses HTTP, password-grant authentication, and shared privileged database credentials; production requires secure transport, identity lifecycle handling, and separate least-privilege database roles.

## 6. Audit Trail

Successful Transaction CREATE, UPDATE, and DELETE operations record before/after audit data as applicable.

Audit records are written within the same database transaction as the business change. If the audit write fails, the business operation rolls back.

Administrative changes are also audited. Audit records retain actor information and survive deletion of the associated business records.

**Trade-off:** Synchronous auditing adds database writes and storage overhead. Application-level auditing does not prevent privileged direct SQL changes and is not a tamper-proof regulatory audit system.

## 7. Deployment and Testing

Flyway manages the PostgreSQL schema. Docker Compose provides a local environment with the API, PostgreSQL, and Keycloak.

The Maven test suite includes unit and PostgreSQL integration tests using Testcontainers. Additional Node tests validate the OpenAPI monetary contract and demo fixtures. GitHub Actions is configured to run automated build and validation checks.

The demo includes deterministic sample data, API verification, and a Postman collection.

**Trade-off:** Docker Compose makes local setup reproducible, but does not provide production deployment, high availability, backups, or operational monitoring.

See [Getting Started](GETTING_STARTED.md) for local setup and [Testing](TESTING.md) for reproducible test commands and final CI results.

## Scope and Limitations

This project implements a simplified USD financial ledger, not a full investment accounting or valuation platform.

Transactions can be corrected or deleted with audit history retained. Negative balances and future-dated entries are permitted. Production accounting rules, immutable reporting periods, idempotency, audit retention, and recovery requirements would need further definition before production use.