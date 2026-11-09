-- Explicit demo command only. Never loaded by Flyway or normal application startup.
\set ON_ERROR_STOP on
BEGIN;
SELECT pg_advisory_xact_lock(hashtext('ark-demo-fixtures-v2'));
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname='ark_demo') THEN
  IF obj_description('ark_demo'::regnamespace, 'pg_namespace') IS DISTINCT FROM 'Ark demo fixture ownership v2' THEN
   RAISE EXCEPTION 'Unowned ark_demo schema; refusing adoption';
  END IF;
 ELSE
  CREATE SCHEMA ark_demo;
  COMMENT ON SCHEMA ark_demo IS 'Ark demo fixture ownership v2';
 END IF;
END $$;
CREATE TABLE IF NOT EXISTS ark_demo.fixtures (
 kind TEXT NOT NULL, fixture_id UUID NOT NULL, payload JSONB NOT NULL,
 PRIMARY KEY(kind,fixture_id)
);
CREATE FUNCTION pg_temp.ensure_demo_fixture(kind TEXT, desired JSONB) RETURNS BOOLEAN
LANGUAGE plpgsql AS $$
DECLARE
 fixture UUID; predicate TEXT; actual JSONB; owned JSONB; normalized JSONB; columns TEXT;
BEGIN
 IF kind NOT IN ('clients','app_users','funds','investors','fund_investors','transactions') THEN
  RAISE EXCEPTION 'Unsupported fixture kind';
 END IF;
 fixture := (desired->>'id')::uuid;
 IF fixture IS NULL THEN RAISE EXCEPTION 'Fixture identity required'; END IF;
 SELECT f.payload INTO owned FROM ark_demo.fixtures f WHERE f.kind=ensure_demo_fixture.kind AND f.fixture_id=fixture;
 IF owned IS NOT NULL AND owned<>desired THEN RAISE EXCEPTION 'Fixture definition drift: % %',kind,fixture; END IF;
 predicate := 'id=($1->>''id'')::uuid';
 EXECUTE format('select to_jsonb(r) from public.%I r where %s',kind,predicate) INTO actual USING desired;
 EXECUTE format('select jsonb_object_agg(j.key,j.value) from jsonb_populate_record(null::public.%I,$1) r cross join lateral jsonb_each(to_jsonb(r)) j where j.key in (select jsonb_object_keys($1))',kind)
  INTO normalized USING desired;
 IF actual IS NOT NULL THEN
  IF owned IS NULL THEN RAISE EXCEPTION 'Unowned fixture collision: % %',kind,fixture; END IF;
  IF NOT actual @> normalized THEN RAISE EXCEPTION 'Existing fixture was changed: % %',kind,fixture; END IF;
  RETURN false;
 END IF;
 IF owned IS NOT NULL THEN RAISE EXCEPTION 'Owned fixture was removed: % %',kind,fixture; END IF;
 SELECT string_agg(quote_ident(key),',' ORDER BY key) INTO columns FROM jsonb_object_keys(desired) key;
 EXECUTE format('insert into public.%I (%s) select %s from jsonb_populate_record(null::public.%I,$1)',kind,columns,columns,kind) USING desired;
 INSERT INTO ark_demo.fixtures(kind,fixture_id,payload) VALUES(kind,fixture,desired);
 RETURN true;
END $$;
CREATE FUNCTION pg_temp.ensure_demo_transaction(desired JSONB, actor UUID, issuer TEXT, subject TEXT)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE inserted BOOLEAN; affected INTEGER;
BEGIN
 inserted:=pg_temp.ensure_demo_fixture('transactions',desired);
 IF inserted THEN
  INSERT INTO transaction_audit(id,client_id,actor_user_id,actor_issuer,actor_subject,transaction_id,operation,
    new_fund_id,new_investor_id,new_date,new_amount,new_type,new_description)
  SELECT md5('ark-demo-audit:'||t.id)::uuid,t.client_id,u.id,u.issuer,u.subject,t.id,'CREATE',
    t.fund_id,t.investor_id,t.transaction_date,t.amount,t.type,t.description
  FROM transactions t JOIN app_users u ON u.id=actor
  JOIN clients owner ON owner.id=t.client_id
  WHERE t.client_id=(desired->>'client_id')::uuid AND t.id=(desired->>'id')::uuid
    AND u.status='ACTIVE' AND u.issuer=ensure_demo_transaction.issuer AND u.subject=ensure_demo_transaction.subject
    AND u.user_type='ARK' AND u.role='ARK_OPERATIONS' AND owner.status='ACTIVE';
  GET DIAGNOSTICS affected=ROW_COUNT;
  IF affected<>1 THEN RAISE EXCEPTION 'Demo fixture actor must be an active authorized administrator'; END IF;
 END IF;
 IF NOT EXISTS (SELECT 1 FROM transaction_audit a WHERE a.client_id=(desired->>'client_id')::uuid
   AND a.transaction_id=(desired->>'id')::uuid AND a.operation='CREATE' AND a.actor_user_id=actor
   AND a.actor_issuer=issuer AND a.actor_subject=subject
   AND a.new_fund_id=(desired->>'fund_id')::uuid AND a.new_investor_id=(desired->>'investor_id')::uuid
   AND a.new_date=(desired->>'transaction_date')::date AND a.new_amount=(desired->>'amount')::numeric
   AND a.new_type=desired->>'type' AND a.new_description IS NOT DISTINCT FROM desired->>'description')
 THEN RAISE EXCEPTION 'Missing or changed fixture CREATE audit'; END IF;
END $$;
