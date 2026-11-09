-- Trusted database operator ONLY. No identity proof is performed by this SQL script.
-- First verify the issuer equals JWT_ISSUER and resolve subject using the provider's
-- authenticated Admin API or a verified provider-issued token; never accept caller claims.
-- Approve only the confirmed user_type and Client ownership. An approval is NOT a User.
-- psql variables: issuer, subject, user_type (ARK/CLIENT), client_id (empty for ARK).
\set ON_ERROR_STOP on
BEGIN;
SELECT pg_advisory_xact_lock(hashtext('ark-initial-administrator'));
INSERT INTO trusted_identities(issuer,subject,user_type,client_id)
VALUES (:'issuer', :'subject', :'user_type', NULLIF(:'client_id','')::uuid);
COMMIT;
-- Existing approvals are intentionally not overwritten. Investigate conflicts manually.
-- Use the authenticated User administration API to bind the approved identity and assign role.
