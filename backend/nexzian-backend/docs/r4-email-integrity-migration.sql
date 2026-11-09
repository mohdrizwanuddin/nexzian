-- =====================================================================================
-- R4: canonical, ASCII-only emails + database CHECK constraints            [PostgreSQL]
-- =====================================================================================
-- NOT EXECUTED by the build or by the application. Run it yourself, deliberately, with psql:
--
--     psql -v ON_ERROR_STOP=1 -f docs/r4-email-integrity-migration.sql <connection options>
--
-- It was written for PostgreSQL but has NOT been run against a live PostgreSQL database while
-- preparing R4 (the automated tests use H2). REHEARSE IT ON A COPY OF THE DATABASE FIRST.
--
-- WHAT IT ENFORCES (and why)
--   users.email and tenants.email must be
--     * canonical:  email = lower(trim(email))          -> users_email_canonical_chk / tenants_email_canonical_chk
--     * supported:  printable ASCII only (0x21-0x7E)     -> users_email_ascii_chk     / tenants_email_ascii_chk
--   The application normalizes (trim + lowercase) and only accepts ASCII addresses (Punycode for
--   international domains). These constraints make the DATABASE enforce the same rules, so no bug,
--   script or manual UPDATE can bring back case-variant duplicates or look-alike Unicode addresses.
--   With the existing UNIQUE constraints on email, "canonical + ASCII" makes uniqueness effectively
--   case-insensitive and race-safe. Hibernate's ddl-auto=update does NOT add CHECK constraints to
--   tables that already exist, which is why this script exists.
--
--   FRESH vs EXISTING DATABASES: a brand-new PostgreSQL database does NOT need this script - Hibernate
--   creates the tables with the same four constraints (the ASCII ones are exactly the regex below).
--   An EXISTING database does need it. Running it on a fresh database is harmless (repeatable).
--   See the runbook section 'Fresh database or existing database?'.
--
-- SAFETY PROPERTIES
--   * It NEVER deletes or merges accounts and never invents or "repairs" an address. The ONLY data
--     change it can make is lowercasing ASCII letters (A-Z -> a-z) in emails that contain nothing
--     else wrong. Whitespace, control characters and non-ASCII characters are NOT rewritten: they
--     make the script STOP, and you decide what to do with those accounts.
--   * The rewrite uses translate() with explicit A-Z -> a-z mappings, NOT lower(): lower() follows
--     the database LC_CTYPE (under a Turkish locale it can turn "I" into a dotless "i").
--   * The whole change runs in ONE transaction, after the tables are locked. Every check below that
--     can fail raises an exception, which aborts the transaction: nothing is changed. With
--     ON_ERROR_STOP the psql session then ends and PostgreSQL rolls the transaction back. Without
--     it, psql keeps going but every later statement fails ("current transaction is aborted") and
--     COMMIT acts as ROLLBACK - still nothing changed, only noisier. Use ON_ERROR_STOP.
--   * It can be re-run: on an already migrated database it changes no data and re-creates the
--     same four constraints (see the locking note in STEP 2).
--
-- SCHEMA ASSUMPTIONS (verify them - STEP 0 prints what this session resolves)
--   * Tables "users" and "tenants", column "email", reached through the session's search_path.
--     The application sets no schema of its own (no default_schema / currentSchema), so it uses
--     its role's default search_path - normally "public". Run this script as the SAME role, or
--     SET search_path accordingly, so you migrate the same tables the application uses.
--   * Existing single-column UNIQUE constraints on users(email) and tenants(email). They are found
--     by COLUMN, not by name, because Hibernate generates their names. If either is missing the
--     script aborts (the uniqueness guarantee would not exist).
--
-- OPTIONAL BACKUP (the script creates NOTHING by itself)
--   If STEP 2 would have to rewrite any row (letters currently upper-case), the original values
--   are lost unless you saved them. The script therefore REFUSES to rewrite unless these two
--   tables exist. Create them yourself BEFORE running the script (it never overwrites: CREATE TABLE
--   fails if the name is taken):
--
--     CREATE TABLE r4_backup_users_email   AS SELECT id, email, now() AS backed_up_at FROM users;
--     CREATE TABLE r4_backup_tenants_email AS SELECT id, email, now() AS backed_up_at FROM tenants;
--
--   If you expect no rewrite (the STEP 1 counts show 0), no backup is required.
-- =====================================================================================


-- =====================================================================================
-- STEP 0 - ENVIRONMENT (read-only). Confirm you are on the right database, role and schema.
-- =====================================================================================

SELECT current_database()                 AS database,
       current_user                       AS role,
       current_schema()                   AS current_schema,
       current_setting('search_path')     AS search_path,
       current_setting('server_version')  AS server_version,
       current_setting('server_encoding') AS server_encoding;

-- Which schema do "users" and "tenants" resolve to for this session? (Both rows must appear.)
SELECT c.relname AS table_name, n.nspname AS schema_name
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.oid IN (to_regclass('users'), to_regclass('tenants'));

-- Informational: the database locale. The rewrite below does not depend on it (see SAFETY).
SHOW lc_ctype;

-- The UNIQUE constraints the guarantee relies on (names are Hibernate-generated). Both tables
-- must show one row each for the column "email".
SELECT c.conrelid::regclass AS table_name, c.conname AS unique_constraint,
       pg_get_constraintdef(c.oid) AS definition
  FROM pg_constraint c
 WHERE c.contype = 'u' AND c.conrelid IN (to_regclass('users'), to_regclass('tenants'));


-- =====================================================================================
-- STEP 1 - READ-ONLY DETAIL REPORTS. All "problem" queries must return 0 rows.
--          (STEP 2 re-checks everything under a table lock; these are for you to read.)
-- =====================================================================================

-- 1a. Values that break the supported-address policy: contain whitespace/control characters,
--     contain non-ASCII characters, or are empty. NOT rewritten by this script: fix them first.
SELECT 'users' AS table_name, id, email AS stored_value,
       CASE WHEN email ~ '[^ -~]' THEN 'non-ASCII or control character'
            ELSE 'whitespace or empty' END AS problem
  FROM users   WHERE email !~ '^[!-~]+$'
UNION ALL
SELECT 'tenants', id, email,
       CASE WHEN email ~ '[^ -~]' THEN 'non-ASCII or control character'
            ELSE 'whitespace or empty' END
  FROM tenants WHERE email !~ '^[!-~]+$';

-- 1b. Rows that would collide after ASCII-lowercasing (two accounts for one address).
SELECT 'users' AS table_name,
       translate(email, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz') AS canonical_email,
       count(*) AS accounts, array_agg(email) AS stored_values
  FROM users
 GROUP BY 2 HAVING count(*) > 1
UNION ALL
SELECT 'tenants',
       translate(email, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'),
       count(*), array_agg(email)
  FROM tenants
 GROUP BY 2 HAVING count(*) > 1;

-- 1c. How many rows STEP 2 would rewrite (0 = nothing changes, no backup needed).
SELECT (SELECT count(*) FROM users
         WHERE email <> translate(email, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')) AS users_to_lowercase,
       (SELECT count(*) FROM tenants
         WHERE email <> translate(email, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')) AS tenants_to_lowercase;

-- 1d. Backup tables that exist (needed only if 1c shows rows to lowercase).
SELECT to_regclass('r4_backup_users_email')   AS users_backup,
       to_regclass('r4_backup_tenants_email') AS tenants_backup;

-- 1e. The four constraint names this script manages, if they already exist (a re-run).
SELECT conrelid::regclass AS table_name, conname, convalidated
  FROM pg_constraint
 WHERE conname IN ('users_email_canonical_chk', 'users_email_ascii_chk',
                   'tenants_email_canonical_chk', 'tenants_email_ascii_chk');


-- =====================================================================================
-- STEP 2 - THE MIGRATION. One transaction: it either fully applies or changes nothing.
-- =====================================================================================
BEGIN;

-- Do not wait forever behind a long-running application transaction: fail (and roll back) instead.
SET LOCAL lock_timeout = '15s';

-- Exclusive lock on both tables, taken up front. Because ALTER TABLE needs ACCESS EXCLUSIVE
-- anyway, taking it immediately avoids a lock-upgrade deadlock with concurrent readers. While it
-- is held NOTHING can read or write users/tenants (logins and registrations wait). Hold time: a
-- few seconds at current table sizes (dominated by the full-table validation of the new CHECKs).
-- Stop the application first if you can.
LOCK TABLE users, tenants IN ACCESS EXCLUSIVE MODE;

-- ---- Guards. Every failure raises an exception => the transaction aborts => nothing changed ----
DO $preflight$
DECLARE
    v_tbl    text;
    v_rel    regclass;
    v_attnum smallint;
    v_count  bigint;
BEGIN
    FOREACH v_tbl IN ARRAY ARRAY['users', 'tenants'] LOOP

        -- The table must be reachable through THIS session's search_path (see STEP 0).
        v_rel := to_regclass(v_tbl);
        IF v_rel IS NULL THEN
            RAISE EXCEPTION 'R4 migration stopped: table "%" not found via search_path (%). Run as the application role or SET search_path to the application schema.',
                v_tbl, current_setting('search_path');
        END IF;

        SELECT attnum INTO v_attnum
          FROM pg_attribute
         WHERE attrelid = v_rel AND attname = 'email' AND NOT attisdropped;
        IF v_attnum IS NULL THEN
            RAISE EXCEPTION 'R4 migration stopped: table % has no column "email".', v_rel;
        END IF;

        -- The global uniqueness guarantee must really exist: a single-column UNIQUE on email.
        -- (A unique INDEX without a constraint is not accepted; create a constraint first.)
        IF NOT EXISTS (SELECT 1 FROM pg_constraint c
                        WHERE c.conrelid = v_rel AND c.contype = 'u' AND c.conkey = ARRAY[v_attnum]) THEN
            RAISE EXCEPTION 'R4 migration stopped: no UNIQUE constraint on %.email. Case-insensitive uniqueness would not be guaranteed. Add the unique constraint first.',
                v_rel;
        END IF;

        -- Supported-address policy: printable ASCII only. Rows violating it are never rewritten.
        EXECUTE format('SELECT count(*) FROM %s WHERE email !~ %L', v_rel, '^[!-~]+$') INTO v_count;
        IF v_count > 0 THEN
            RAISE EXCEPTION 'R4 migration stopped: % row(s) in % contain whitespace, control characters, non-ASCII characters or are empty (see STEP 1a). Resolve them manually; this script will not change them.',
                v_count, v_rel;
        END IF;

        -- Two rows that differ only by ASCII case would collapse into one address.
        EXECUTE format(
            'SELECT count(*) FROM (SELECT 1 FROM %s GROUP BY translate(email, %L, %L) HAVING count(*) > 1) d',
            v_rel, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz') INTO v_count;
        IF v_count > 0 THEN
            RAISE EXCEPTION 'R4 migration stopped: % group(s) of rows in % differ only by letter case (see STEP 1b). Resolve them manually; this script never merges accounts.',
                v_count, v_rel;
        END IF;

        -- A rewrite loses the original capitalization: demand a backup first.
        EXECUTE format('SELECT count(*) FROM %s WHERE email <> translate(email, %L, %L)',
                       v_rel, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz') INTO v_count;
        IF v_count > 0 AND to_regclass('r4_backup_' || v_tbl || '_email') IS NULL THEN
            RAISE EXCEPTION 'R4 migration stopped: % row(s) in % would be lowercased but backup table r4_backup_%_email does not exist. Create it (see the header) and re-run.',
                v_count, v_rel, v_tbl;
        END IF;
    END LOOP;
END
$preflight$;

-- ---- The only data change: ASCII-lowercase letters (explicit mapping, locale independent) ----
-- By now the guards proved every row is printable ASCII, so nothing else about an address changes.
UPDATE users
   SET email = translate(email, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')
 WHERE email <> translate(email, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz');
UPDATE tenants
   SET email = translate(email, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')
 WHERE email <> translate(email, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz');

-- ---- Constraints. DROP ... IF EXISTS + ADD makes the script safely repeatable. -----------------
-- The drop and the re-create happen inside this transaction while both tables are locked
-- exclusively, so no write can slip in between; the resulting constraints are exactly as strong
-- as on a first run. ADD validates every existing row (a full scan under the lock). For a very
-- large table you could use ADD CONSTRAINT ... NOT VALID and VALIDATE CONSTRAINT later to shorten
-- the lock; not needed at current volumes.
--
--   canonical:  lower(trim()) - the application's normalization rule (kept from the first R4 script)
--   ascii:      only printable ASCII 0x21-0x7E, one or more characters. This single rule rejects
--               spaces, tabs, newlines, DEL and other control characters, and every non-ASCII
--               character. (PostgreSQL's "$" matches only at the very end of the string, so a
--               trailing newline is rejected too.)
ALTER TABLE users   DROP CONSTRAINT IF EXISTS users_email_canonical_chk;
ALTER TABLE users   DROP CONSTRAINT IF EXISTS users_email_ascii_chk;
ALTER TABLE users   ADD CONSTRAINT users_email_canonical_chk   CHECK (email = lower(trim(email)));
ALTER TABLE users   ADD CONSTRAINT users_email_ascii_chk       CHECK (email ~ '^[!-~]+$');

ALTER TABLE tenants DROP CONSTRAINT IF EXISTS tenants_email_canonical_chk;
ALTER TABLE tenants DROP CONSTRAINT IF EXISTS tenants_email_ascii_chk;
ALTER TABLE tenants ADD CONSTRAINT tenants_email_canonical_chk CHECK (email = lower(trim(email)));
ALTER TABLE tenants ADD CONSTRAINT tenants_email_ascii_chk     CHECK (email ~ '^[!-~]+$');

-- ---- Post-conditions, still inside the transaction: if any is false, nothing is committed ------
DO $postcheck$
DECLARE
    v_name  text;
    v_count bigint;
BEGIN
    FOREACH v_name IN ARRAY ARRAY['users_email_canonical_chk', 'users_email_ascii_chk',
                                  'tenants_email_canonical_chk', 'tenants_email_ascii_chk'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_constraint
                        WHERE conname = v_name AND contype = 'c' AND convalidated) THEN
            RAISE EXCEPTION 'R4 migration stopped: constraint % is missing or not validated.', v_name;
        END IF;
    END LOOP;

    SELECT (SELECT count(*) FROM users   WHERE email !~ '^[!-~]+$' OR email <> lower(trim(email)))
         + (SELECT count(*) FROM tenants WHERE email !~ '^[!-~]+$' OR email <> lower(trim(email)))
      INTO v_count;
    IF v_count > 0 THEN
        RAISE EXCEPTION 'R4 migration stopped: % row(s) still violate the policy after the migration.', v_count;
    END IF;
END
$postcheck$;

COMMIT;


-- =====================================================================================
-- STEP 3 - POST-MIGRATION VERIFICATION (read-only except for the self-rolling-back probe).
-- =====================================================================================

-- 3a. All four constraints exist and are validated (convalidated = true) with these definitions.
SELECT conrelid::regclass AS table_name, conname, convalidated, pg_get_constraintdef(oid) AS definition
  FROM pg_constraint
 WHERE conname IN ('users_email_canonical_chk', 'users_email_ascii_chk',
                   'tenants_email_canonical_chk', 'tenants_email_ascii_chk')
 ORDER BY conrelid::regclass::text, conname;

-- 3b. Nothing non-canonical or unsupported remains (both counts must be 0).
SELECT (SELECT count(*) FROM users   WHERE email !~ '^[!-~]+$' OR email <> lower(trim(email))) AS users_left,
       (SELECT count(*) FROM tenants WHERE email !~ '^[!-~]+$' OR email <> lower(trim(email))) AS tenants_left;

-- 3c. The constraints really bite. Self-contained probe: each invalid UPDATE (append a space, a
--     tab, a newline, a non-ASCII letter, or an upper-case letter) must be rejected with a
--     check_violation, which is caught INSIDE the block. If an invalid update were ever ACCEPTED,
--     the block raises an exception; the exception also undoes the update, because it aborts the
--     whole DO statement. So no probe value can persist, whatever the client's transaction mode
--     (autocommit GUI, explicit transaction, psql). Empty tables are skipped with a NOTICE.
DO $probe$
DECLARE
    v_tbl text;
    v_bad text;
    v_id  uuid;
BEGIN
    FOREACH v_tbl IN ARRAY ARRAY['users', 'tenants'] LOOP
        EXECUTE format('SELECT id FROM %I LIMIT 1', v_tbl) INTO v_id;
        IF v_id IS NULL THEN
            RAISE NOTICE 'R4 probe: table % is empty - nothing to probe', v_tbl;
            CONTINUE;
        END IF;

        FOREACH v_bad IN ARRAY ARRAY[' ', E'\t', E'\n', chr(233), 'X'] LOOP
            BEGIN
                EXECUTE format('UPDATE %I SET email = email || $1 WHERE id = $2', v_tbl) USING v_bad, v_id;
                RAISE EXCEPTION 'R4 probe FAILED: table % ACCEPTED an invalid email (suffix %). The constraints are not working.',
                    v_tbl, quote_literal(v_bad);
            EXCEPTION WHEN check_violation THEN
                RAISE NOTICE 'R4 probe ok: table % rejected suffix %', v_tbl, quote_literal(v_bad);
            END;
        END LOOP;
    END LOOP;
END
$probe$;

-- 3d. Row counts (compare with the numbers you noted before the migration).
SELECT (SELECT count(*) FROM users) AS users, (SELECT count(*) FROM tenants) AS tenants;

-- 3e. The UNIQUE constraints are still in place.
SELECT c.conrelid::regclass AS table_name, c.conname AS unique_constraint
  FROM pg_constraint c
 WHERE c.contype = 'u' AND c.conrelid IN (to_regclass('users'), to_regclass('tenants'));


-- =====================================================================================
-- ROLLBACK (only if you must undo the DATABASE side; read the runbook first). NOT part of the run.
-- =====================================================================================
-- 1) Remove the constraints (safe; touches no data). The emails stay lowercase:
--
--      ALTER TABLE users   DROP CONSTRAINT IF EXISTS users_email_ascii_chk;
--      ALTER TABLE users   DROP CONSTRAINT IF EXISTS users_email_canonical_chk;
--      ALTER TABLE tenants DROP CONSTRAINT IF EXISTS tenants_email_ascii_chk;
--      ALTER TABLE tenants DROP CONSTRAINT IF EXISTS tenants_email_canonical_chk;
--
-- 2) ONLY if STEP 2 actually lowercased rows AND you created the backup tables, restore the
--    original capitalization (after step 1, otherwise the canonical CHECK refuses it):
--
--      UPDATE users   u SET email = b.email FROM r4_backup_users_email   b WHERE b.id = u.id AND u.email <> b.email;
--      UPDATE tenants t SET email = b.email FROM r4_backup_tenants_email b WHERE b.id = t.id AND t.email <> b.email;
--
-- 3) Drop the backup tables once you are satisfied (the script never does):
--
--      DROP TABLE IF EXISTS r4_backup_users_email, r4_backup_tenants_email;
