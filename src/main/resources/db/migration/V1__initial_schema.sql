-- Fresh databases only. Never baseline/repair an existing V1-V3 database into this schema.
CREATE TABLE clients (
 id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL CHECK (name ~ '[^[:space:]]'),
 status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE app_users (
 id UUID PRIMARY KEY, issuer VARCHAR(1000) NOT NULL CHECK (issuer <> ''),
 subject VARCHAR(255) NOT NULL CHECK (subject <> ''),
 user_type VARCHAR(16) NOT NULL CHECK (user_type IN ('CLIENT','ARK')),
 client_id UUID REFERENCES clients(id) ON DELETE RESTRICT,
 role VARCHAR(32) NOT NULL,
 CONSTRAINT ck_user_ownership_role CHECK (
  (user_type='CLIENT' AND client_id IS NOT NULL AND role IN ('CLIENT_ADMIN','CLIENT_MANAGER','CLIENT_REPORT_VIEWER')) OR
  (user_type='ARK' AND client_id IS NULL AND role IN ('ARK_ADMIN','ARK_OPERATIONS','ARK_REPORT_VIEWER'))),
 status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','DISABLED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), UNIQUE (issuer, subject)
);
CREATE INDEX ix_app_users_client ON app_users(client_id,id);

-- UUIDs are supplied by the application; no PostgreSQL extension is required.
CREATE TABLE funds (
    client_id UUID NOT NULL REFERENCES clients(id) ON DELETE RESTRICT,
    id UUID PRIMARY KEY,
    CONSTRAINT uq_funds_client_id UNIQUE (client_id, id),
    name VARCHAR(200) NOT NULL,
    description VARCHAR(1000),
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    CONSTRAINT ck_funds_name CHECK (name ~ '[^[:space:]]'),
    CONSTRAINT ck_funds_currency CHECK (currency = 'USD')
);

CREATE TABLE investors (
    client_id UUID NOT NULL REFERENCES clients(id) ON DELETE RESTRICT,
    id UUID PRIMARY KEY,
    CONSTRAINT uq_investors_client_id UNIQUE (client_id, id),
    name VARCHAR(200) NOT NULL,
    email VARCHAR(254) NOT NULL,
    CONSTRAINT ck_investors_name CHECK (name ~ '[^[:space:]]'),
    CONSTRAINT ck_investors_email_nonblank CHECK (email ~ '[^[:space:]]')
);

CREATE TABLE fund_investors (
    client_id UUID NOT NULL REFERENCES clients(id) ON DELETE RESTRICT,
    id UUID PRIMARY KEY,
    fund_id UUID NOT NULL,
    investor_id UUID NOT NULL,
    CONSTRAINT uq_fund_investors_pair UNIQUE (client_id, fund_id, investor_id),
    CONSTRAINT fk_fund_investors_fund FOREIGN KEY (client_id, fund_id) REFERENCES funds (client_id, id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_fund_investors_investor FOREIGN KEY (client_id, investor_id) REFERENCES investors (client_id, id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE TABLE transactions (
    client_id UUID NOT NULL REFERENCES clients(id) ON DELETE RESTRICT,
    id UUID PRIMARY KEY,
    fund_id UUID NOT NULL,
    investor_id UUID NOT NULL,
    transaction_date DATE NOT NULL,
    amount NUMERIC(18,2) NOT NULL,
    type VARCHAR(32) NOT NULL,
    description VARCHAR(1000),
    CONSTRAINT ck_transactions_amount CHECK (amount > 0 AND amount <= 9999999999999999.99),
    CONSTRAINT ck_transactions_type CHECK (type IN (
        'CONTRIBUTION', 'INTEREST_INCOME', 'DISTRIBUTION',
        'GENERAL_EXPENSE', 'MANAGEMENT_FEE'
    )),
    CONSTRAINT ck_transactions_date CHECK (
        transaction_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'
    ),
    CONSTRAINT fk_transactions_participation FOREIGN KEY (client_id, fund_id, investor_id)
        REFERENCES fund_investors (client_id, fund_id, investor_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);

-- The pair's unique index already supports participation lookups by fund.
CREATE INDEX ix_fund_investors_investor_fund ON fund_investors (client_id, investor_id, fund_id);

-- UUID tie breakers allow stable ordering for equal ledger dates.
CREATE INDEX ix_transactions_fund_date_id ON transactions (client_id, fund_id, transaction_date, id);
CREATE INDEX ix_transactions_investor_date_id ON transactions (client_id, investor_id, transaction_date, id);
CREATE INDEX ix_transactions_date_id ON transactions (client_id, transaction_date, id);
-- Supports pair-specific reports and restrictive participation deletion checks.
CREATE INDEX ix_transactions_pair_date_id
    ON transactions (client_id, fund_id, investor_id, transaction_date, id);

-- Deliberately no foreign keys: evidence survives transaction and owner deletion.
-- Existing ledger rows are not backfilled with invented CREATE events.
CREATE TABLE transaction_audit (
    client_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    actor_issuer VARCHAR(1000) NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL,
    operation VARCHAR(6) NOT NULL CHECK (operation IN ('CREATE', 'UPDATE', 'DELETE')),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    previous_fund_id UUID,
    previous_investor_id UUID,
    previous_date DATE,
    previous_amount NUMERIC(18,2),
    previous_type VARCHAR(32),
    previous_description VARCHAR(1000),
    new_fund_id UUID,
    new_investor_id UUID,
    new_date DATE,
    new_amount NUMERIC(18,2),
    new_type VARCHAR(32),
    new_description VARCHAR(1000),
    CONSTRAINT ck_audit_previous_shape CHECK (
        (operation = 'CREATE' AND previous_fund_id IS NULL AND previous_investor_id IS NULL
            AND previous_date IS NULL AND previous_amount IS NULL
            AND previous_type IS NULL AND previous_description IS NULL)
        OR (operation IN ('UPDATE', 'DELETE') AND previous_fund_id IS NOT NULL
            AND previous_investor_id IS NOT NULL AND previous_date IS NOT NULL
            AND previous_amount IS NOT NULL AND previous_type IS NOT NULL)
    ),
    CONSTRAINT ck_audit_new_shape CHECK (
        (operation = 'DELETE' AND new_fund_id IS NULL AND new_investor_id IS NULL
            AND new_date IS NULL AND new_amount IS NULL
            AND new_type IS NULL AND new_description IS NULL)
        OR (operation IN ('CREATE', 'UPDATE') AND new_fund_id IS NOT NULL
            AND new_investor_id IS NOT NULL AND new_date IS NOT NULL
            AND new_amount IS NOT NULL AND new_type IS NOT NULL)
    ),
    CONSTRAINT ck_audit_amounts CHECK (
        (previous_amount > 0 AND previous_amount <= 9999999999999999.99)
        AND (new_amount > 0 AND new_amount <= 9999999999999999.99)
    ),
    CONSTRAINT ck_audit_dates CHECK (
        previous_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'
        AND new_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'
    ),
    CONSTRAINT ck_audit_types CHECK (
        previous_type IN ('CONTRIBUTION', 'INTEREST_INCOME', 'DISTRIBUTION', 'GENERAL_EXPENSE', 'MANAGEMENT_FEE')
        AND new_type IN ('CONTRIBUTION', 'INTEREST_INCOME', 'DISTRIBUTION', 'GENERAL_EXPENSE', 'MANAGEMENT_FEE')
    ),
    CONSTRAINT ck_audit_owners CHECK (
        operation <> 'UPDATE' OR
        (previous_fund_id = new_fund_id AND previous_investor_id = new_investor_id)
    )
);

CREATE INDEX ix_transaction_audit_transaction_time
    ON transaction_audit (client_id, transaction_id, occurred_at, id);
