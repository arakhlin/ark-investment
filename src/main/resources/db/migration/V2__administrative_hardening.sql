-- Only trusted operators may approve identities after checking the external provider.
-- Application administration has no endpoint for writing this registry.
CREATE TABLE trusted_identities (
    issuer varchar(1000) NOT NULL,
    subject varchar(255) NOT NULL,
    user_type varchar(10) NOT NULL CHECK (user_type IN ('ARK','CLIENT')),
    client_id uuid REFERENCES clients(id),
    verified_at timestamptz NOT NULL DEFAULT current_timestamp,
    PRIMARY KEY (issuer, subject),
    CHECK ((user_type = 'ARK' AND client_id IS NULL) OR (user_type = 'CLIENT' AND client_id IS NOT NULL))
);

CREATE TABLE administrative_audit (
    id uuid PRIMARY KEY,
    actor_user_id uuid NOT NULL,
    actor_issuer varchar(1000) NOT NULL,
    actor_subject varchar(255) NOT NULL,
    target_type varchar(10) NOT NULL CHECK (target_type IN ('CLIENT','USER')),
    target_id uuid NOT NULL,
    operation varchar(10) NOT NULL CHECK (operation IN ('CREATE','UPDATE')),
    previous_values jsonb,
    new_values jsonb NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT current_timestamp,
    CHECK ((operation = 'CREATE' AND previous_values IS NULL) OR (operation = 'UPDATE' AND previous_values IS NOT NULL))
);
CREATE INDEX administrative_audit_target ON administrative_audit(target_type,target_id,occurred_at);
