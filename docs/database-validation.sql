-- Run with psql -v ON_ERROR_STOP=1 against a migrated disposable database.
-- Fixtures and helper are rolled back; existing records are never modified.
BEGIN;
CREATE FUNCTION pg_temp.expect_failure(statement TEXT, expected_state TEXT)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE actual_state TEXT;
BEGIN
    BEGIN
        EXECUTE statement;
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS actual_state = RETURNED_SQLSTATE;
        IF actual_state = expected_state THEN RETURN; END IF;
        RAISE EXCEPTION 'Expected SQLSTATE %, got % for %', expected_state, actual_state, statement;
    END;
    RAISE EXCEPTION 'Expected SQLSTATE % but statement succeeded: %', expected_state, statement;
END;
$$;

DO $$
DECLARE
    client UUID := gen_random_uuid();
    f UUID := gen_random_uuid();
    i UUID := gen_random_uuid();
    f2 UUID := gen_random_uuid();
    i2 UUID := gen_random_uuid();
    t UUID := gen_random_uuid();
    kind TEXT;
    bad_amount TEXT;
    insert_tx TEXT;
BEGIN
    INSERT INTO clients(id,name,status) VALUES (client, 'Validation client', 'ACTIVE');
    INSERT INTO funds (client_id, id, name) VALUES (client, f, 'Validation fund'), (client, f2, 'Other fund');
    INSERT INTO investors (client_id, id, name, email) VALUES
        (client, i, 'Validation investor', 'same@example.com'), (client, i2, 'Other investor', 'same@example.com');
    INSERT INTO fund_investors (client_id, id, fund_id, investor_id) VALUES
        (client, gen_random_uuid(), f, i), (client, gen_random_uuid(), f, i2), (client, gen_random_uuid(), f2, i2);
    -- Many-to-many exists, but the pair (f2, i) does not.
    insert_tx := format('INSERT INTO transactions (client_id, id, fund_id, investor_id, transaction_date, amount, type) VALUES (%L, %L, %L, %L, %L, %%s, %%L)',
        client, t, f, i, '2026-11-06');
    FOREACH kind IN ARRAY ARRAY['CONTRIBUTION', 'INTEREST_INCOME', 'DISTRIBUTION', 'GENERAL_EXPENSE', 'MANAGEMENT_FEE'] LOOP
        INSERT INTO transactions (client_id, id, fund_id, investor_id, transaction_date, amount, type)
        VALUES (client, gen_random_uuid(), f, i, '2026-11-06', 0.01, kind);
    END LOOP;
    EXECUTE format(insert_tx, '9999999999999999.99', 'CONTRIBUTION');
    FOREACH bad_amount IN ARRAY ARRAY['0', '-0.01', '''NaN'''] LOOP
        PERFORM pg_temp.expect_failure(format(insert_tx, bad_amount, 'CONTRIBUTION'), '23514');
    END LOOP;
    FOREACH bad_amount IN ARRAY ARRAY['''Infinity''', '''-Infinity'''] LOOP
        PERFORM pg_temp.expect_failure(format(insert_tx, bad_amount, 'CONTRIBUTION'), '22003');
    END LOOP;
    PERFORM pg_temp.expect_failure(format(insert_tx, '10000000000000000.00', 'CONTRIBUTION'), '22003');
    PERFORM pg_temp.expect_failure(format(insert_tx, 'NULL', 'CONTRIBUTION'), '23502');
    PERFORM pg_temp.expect_failure(format(insert_tx, '1.00', 'INVALID'), '23514');
    PERFORM pg_temp.expect_failure(format('UPDATE transactions SET fund_id = %L WHERE id = %L', f2, t), '23503');
    PERFORM pg_temp.expect_failure(format('DELETE FROM funds WHERE id = %L', f), '23503');
    PERFORM pg_temp.expect_failure(format('DELETE FROM investors WHERE id = %L', i), '23503');
    PERFORM pg_temp.expect_failure(format('DELETE FROM fund_investors WHERE fund_id = %L AND investor_id = %L', f, i), '23503');
    PERFORM pg_temp.expect_failure(format('UPDATE fund_investors SET fund_id = %L WHERE fund_id = %L AND investor_id = %L', f2, f, i), '23503');
    PERFORM pg_temp.expect_failure(format('INSERT INTO fund_investors (client_id,id,fund_id,investor_id) VALUES (%L, %L, %L, %L)', client, gen_random_uuid(), f, i), '23505');
    PERFORM pg_temp.expect_failure(format('INSERT INTO fund_investors (client_id,id,fund_id,investor_id) VALUES (%L, %L, %L, %L)', client, gen_random_uuid(), gen_random_uuid(), i), '23503');
    PERFORM pg_temp.expect_failure(format('INSERT INTO fund_investors (client_id,id,fund_id,investor_id) VALUES (%L, %L, %L, %L)', client, gen_random_uuid(), f, gen_random_uuid()), '23503');
    PERFORM pg_temp.expect_failure(format('UPDATE transactions SET transaction_date = ''infinity'' WHERE id = %L', t), '23514');
    IF (SELECT SUM(amount) FROM transactions WHERE fund_id = f) <= 9999999999999999.99 THEN
        RAISE EXCEPTION 'Aggregate must exceed individual amount limit';
    END IF;
    -- Cleanup is possible in dependency order; no cascade is required.
    DELETE FROM transactions WHERE fund_id = f;
    DELETE FROM fund_investors WHERE fund_id IN (f, f2);
    DELETE FROM funds WHERE id IN (f, f2);
    DELETE FROM investors WHERE id IN (i, i2);
END;
$$;
ROLLBACK;
