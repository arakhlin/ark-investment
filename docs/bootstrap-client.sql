-- Trusted operator only: provision the initial ARK_ADMIN. No public bootstrap endpoint.
-- Required psql variables: issuer, subject. Verify identity with Keycloak first.
\set ON_ERROR_STOP on
BEGIN;
SELECT pg_advisory_xact_lock(hashtext('ark-initial-administrator'));
INSERT INTO trusted_identities(issuer,subject,user_type,client_id)
VALUES (:'issuer', :'subject', 'ARK', NULL) ON CONFLICT DO NOTHING;
INSERT INTO app_users(id,issuer,subject,user_type,client_id,role,status)
VALUES(gen_random_uuid(), :'issuer', :'subject', 'ARK', NULL, 'ARK_ADMIN', 'ACTIVE')
ON CONFLICT(issuer,subject) DO NOTHING;
SELECT EXISTS(SELECT 1 FROM app_users WHERE issuer=:'issuer' AND subject=:'subject'
 AND user_type='ARK' AND client_id IS NULL AND role='ARK_ADMIN' AND status='ACTIVE') AS matches \gset
\if :matches
\else
ROLLBACK;
DO $$ BEGIN RAISE EXCEPTION 'Bootstrap identity is disabled or incompatible'; END $$;
\endif
COMMIT;
-- ARK_ADMIN creates a Client, then its first active CLIENT_ADMIN using POST /api/v1/users.
