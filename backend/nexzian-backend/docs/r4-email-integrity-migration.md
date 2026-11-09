# R4: canonical, ASCII-only emails and account-existence protection - deployment runbook

Companion to `r4-email-integrity-migration.sql`. Read both before deploying.

> **The migration SQL has never been run against a PostgreSQL database by the authors.** The
> automated tests use H2, which cannot reproduce PostgreSQL behaviour for these statements.
> **Rehearse it against a copy of your database first** (restore a `pg_dump` into a scratch database
> and run the whole procedure there), and only then run it for real.

## What changed

1. **One canonical email.** Every email is stored and compared as `trim()` + lowercase
   (`Locale.ROOT`), via `com.nexzian.util.EmailNormalizer`: agency registration (owner and agency
   contact email), team-member creation, login, user-details loading (the JWT filter), the
   platform-admin bootstrap, the login-lockout key, and the `User`/`Tenant` entity setters.
2. **Login is case-insensitive.** Users can type their address in any casing.
3. **Supported-address policy: ASCII only.** An email must be printable ASCII (0x21-0x7E), contain
   no whitespace or control characters, and be at most 150 characters. Non-ASCII input is rejected
   with **HTTP 400** ("Use an email address with ASCII characters only."). Internationalized domains
   must be given in Punycode (`xn--...`) form. Enforced in three places that share one definition
   (`EmailNormalizer`): request validation (`@AsciiEmail` on the registration and team-member
   requests), the service/entity layer (`normalizeForStorage`, so internal callers cannot bypass
   it), and the database. **Login is not restricted**: a non-ASCII login attempt simply matches no
   account (401), so nothing about existing accounts changes.
4. **Database enforcement.** On `users` and `tenants`: `*_email_canonical_chk`
   (`email = lower(trim(email))`) and `*_email_ascii_chk` (printable ASCII only), on top of the
   existing global UNIQUE constraints. Together they make uniqueness case-insensitive and race-safe.
5. **No account-existence oracle in team-member creation.** A taken address (this agency, another
   agency, a platform account, a case variant, or a lost concurrent race) always returns the same
   generic `409`; a case variant can no longer cause HTTP 500.
6. **Team-member creation is rate limited:** 30 attempts per agency per hour (see below).

Why ASCII only: lowercase is identical in Java and PostgreSQL only for ASCII. Beyond that the two
disagree in edge cases (a value Java produced could be rejected by the CHECK), and Unicode adds
identity problems lowercasing cannot solve: composed vs decomposed forms of one visible address,
a Unicode domain vs its Punycode form, full-width letters, and cross-script look-alikes of someone
else's address.

Public agency registration keeps its existing, explicit duplicate message ("An account with this
email already exists"); that is the S2/S5 decision, and it is limited to 5 per IP per hour.

## Schema assumptions (verify them)

The script addresses `users`, `tenants` and the column `email` without a schema name, exactly like the
application. The application configures **no schema** (no `default_schema` / `currentSchema`), so both
use the connecting role's default `search_path` (normally `public`).

* **Verify:** STEP 0 of the script prints `current_schema()`, the `search_path`, and the schema each
  table resolves to. Check it is the schema your application uses. Run the script as the **same
  database role** the application uses, or `SET search_path TO <schema>;` first.
* **Unique constraints:** the script requires a single-column UNIQUE **constraint** on `users(email)`
  and `tenants(email)` and aborts with a clear message if one is missing. They are looked up by
  column, because Hibernate generates the names. A unique *index* without a constraint is not
  accepted.
* **Encoding:** the database should be UTF8 (STEP 0 shows `server_encoding`). The self-test probe in
  STEP 3c uses a non-ASCII character and fails safely (nothing changes) on a non-UTF8 database.

## Procedure

1. **Back up the database** (`pg_dump`). Stop the application if you can: the script takes an
   exclusive lock on both tables for its duration (a few seconds at current sizes), so logins and
   registrations would wait.
2. **Rehearse on a copy** (see the warning at the top).
3. **Pre-checks (read-only).** Run the script's STEP 0 and STEP 1 reports (or just run the whole file;
   STEP 2 repeats every check under the lock). You are looking for:
   * STEP 1a: **0 rows** - no existing email with whitespace, control or non-ASCII characters.
   * STEP 1b: **0 rows** - no two rows that differ only by letter case.
   * STEP 1c: how many rows will be lowercased. **If non-zero**, create the backup tables first
     (below); the script refuses to rewrite without them.
   Your earlier checks reported 0 duplicate and 0 non-canonical rows; the new checks add the
   whitespace and non-ASCII tests, so run them too.
4. **Optional backup tables** - the script never creates them. Only needed if STEP 1c is non-zero:
   ```sql
   CREATE TABLE r4_backup_users_email   AS SELECT id, email, now() AS backed_up_at FROM users;
   CREATE TABLE r4_backup_tenants_email AS SELECT id, email, now() AS backed_up_at FROM tenants;
   ```
   The names are prefixed `r4_backup_`; `CREATE TABLE` fails if a table already exists, so nothing
   is overwritten.
5. **Execute:**
   ```
   psql -v ON_ERROR_STOP=1 -f docs/r4-email-integrity-migration.sql <connection options>
   ```
6. **Post-checks** are STEP 3 of the same file and print as it runs: constraints exist and are
   validated (3a), no violating rows remain (3b), the self-test probe reports `rejected` for each
   invalid value (3c, a self-contained `DO` block), row counts match what you noted (3d), the
   UNIQUE constraints are still present (3e).
7. **Deploy** the R4 backend, then the frontend (the frontend needs no change).
8. **Smoke test:** sign in with a mixed-case spelling of your address; add a team member; repeat it
   and confirm the generic 409; try a non-ASCII address and confirm the 400.

## Fresh database or existing database? (setup procedures)

Both end with **the same four constraints** on `users` and `tenants`: `users_email_canonical_chk`,
`users_email_ascii_chk`, `tenants_email_canonical_chk`, `tenants_email_ascii_chk`, with the ASCII ones
being exactly `CHECK (email ~ '^[!-~]+$')` on PostgreSQL.

| Situation | What creates the constraints | What you do |
|---|---|---|
| **Fresh PostgreSQL database** (empty; the tables do not exist yet) | **Hibernate**, when the application starts with `ddl-auto=update` and creates the tables. The entities declare the rule and a PostgreSQL-specific override, so the generated `CREATE TABLE` carries the full regex rule. | Start the application once. Then run the **verification queries** below. No migration script is needed. |
| **Existing PostgreSQL database** (tables already contain data) | **This migration script.** `ddl-auto=update` never adds checks to a table that already exists. | Follow the **Procedure** above (pre-checks, optional backup, execute, post-checks). |
| **H2 / automated tests** | Hibernate (`create-drop`), using the portable spelling. | Nothing. |

Running the script on a fresh database after the application has created the tables is harmless: it
changes no data and re-creates the same constraints (it is repeatable).

**Verification queries (PostgreSQL)** - run after a fresh start or after the migration. Expect 4 rows,
all with `convalidated = true`, the two ASCII rows showing the regex `email ~ '^[!-~]+$'` (PostgreSQL may print it with a `::text` cast and extra parentheses):

```sql
SELECT conrelid::regclass AS table_name, conname, convalidated, pg_get_constraintdef(oid) AS definition
  FROM pg_constraint
 WHERE conname IN ('users_email_canonical_chk', 'users_email_ascii_chk',
                   'tenants_email_canonical_chk', 'tenants_email_ascii_chk')
 ORDER BY conrelid::regclass::text, conname;
```

If a fresh database shows a long `octet_length(...) ... position(chr(...))` definition instead of the
regex for an ASCII constraint, the PostgreSQL override was not applied: run this migration script, which
replaces the constraint with the regex, and report it.

Do **not** run a fresh application start against an existing database expecting it to add the checks:
it will not.
## Transaction behaviour and safe aborts

* STEP 2 is **one transaction**: `BEGIN; SET LOCAL lock_timeout; LOCK ...; guards; rewrite;
  constraints; post-checks; COMMIT;`. It is all-or-nothing.
* **Every failing guard raises an exception**, and an exception aborts the transaction, so nothing
  is changed. The guards are: tables and `email` columns found; UNIQUE constraints present;
  no whitespace/control/non-ASCII/empty values; no case-only duplicates; backup tables present if
  rows would be rewritten; and after the change, all four constraints validated and no violating row.
* **The checks run after the lock is taken**, so data cannot change between checking and changing.
* `lock_timeout` is 15 s: if the application holds a conflicting lock for longer, the script fails and
  rolls back instead of waiting indefinitely.
* With `ON_ERROR_STOP=1` psql exits at the first error and PostgreSQL rolls the open transaction
  back. Without it psql continues, every later statement fails ("current transaction is aborted") and
  `COMMIT` acts as `ROLLBACK`: still nothing changed, only noisier.
* **The only data change is** lowercasing ASCII letters with `translate(email, 'A-Z', 'a-z')`. It
  never touches an address that has whitespace or non-ASCII characters (those stop the script), never
  deletes, merges or disables an account, and does not use `lower()`, which follows the database
  locale (under a Turkish `LC_CTYPE`, `lower('I')` can give a dotless "i").
* **Re-running** is safe: no data changes on a migrated database, and the four constraints are dropped
  and re-created inside the same locked transaction (so the result is exactly as strong, and no write
  can slip in between). The re-creation re-validates every row under the exclusive lock.

## Rollback

* **Database constraints only:** dropping them is safe and touches no data (statements at the end of
  the `.sql`). Emails stay lowercase.
* **Restoring original capitalization:** only possible if STEP 2 lowercased rows **and** you created the
  backup tables. Drop the constraints first (the canonical CHECK would refuse the old values), then run
  the restore `UPDATE`s shown at the end of the `.sql`. Drop the backup tables afterwards by hand.
* **Application:** rolling back to the pre-R4 build works against the migrated data (all emails are
  already lowercase) with these differences: the old login is **case-sensitive** (users must type the
  lowercase address), the old team-member check is case-sensitive, and ASCII-only is no longer enforced
  by the application (the database constraints still enforce it).
* Do not keep the constraints dropped while running the new build long-term: they are what protect
  against case-variant and look-alike duplicates written by anything other than the application.

## Rate limiter

* Key: the authenticated owner's **tenant id**, read from the database user record, never from the
  request. No client IP or forwarded header is used (the endpoint is authenticated).
* Threshold: **30 attempts per agency per rolling hour**
  (`app.security.team-members.max-attempts-per-tenant`, `app.security.team-members.window-minutes`;
  defaults 30 and 60). Real onboarding adds employees a few at a time; the limit is far above that but
  stops bulk probing.
* Counts valid requests only (past bean validation and authorization), successes **and** failures, and
  runs **before** the email lookup. Returns 429 with `Retry-After`.
* **In-memory and per application instance.** Counters reset when the app restarts, and with several
  instances an agency could use the limit once per instance. A shared store (database or Redis) is
  the upgrade path if the app is scaled out. Memory is bounded (`AttemptStore`,
  `app.security.max-tracked-keys`, default 50 000; overflow agencies share one bucket instead of
  becoming unlimited).

## JWT / sessions

Existing access tokens carry the stored email as their subject. Stored emails were already lowercase,
so existing tokens keep working. Had any mixed-case rows been rewritten, tokens issued before the
rewrite would fail their lookup once; the 15-minute access token expires and the refresh flow issues a
new one (refresh looks the user up by id).

## Remaining limits

* **Not executed against PostgreSQL by the authors** (see the top). Tests generate the schema Hibernate
  would create for PostgreSQL as text and compare it with this script, but no statement has been
  run on a PostgreSQL server: a rehearsal is still required for both a fresh and a migrated database.
* **One rule, two spellings.** The ASCII rule is the PostgreSQL regex `email ~ '^[!-~]+$'` on PostgreSQL
  and an equivalent built from `octet_length`/`char_length`/`position(chr(n) ...)` on H2 (the test
  database); automated tests prove both accept exactly the characters the application policy accepts
  (all control characters, DEL and non-ASCII included). See `EmailConstraints` for the reasoning.
* **The portable spelling assumes a UTF-8 database** (it detects non-ASCII by comparing byte and
  character length). The PostgreSQL regex does not depend on the encoding; PostgreSQL schemas always
  get the regex.
* **Enforcement of the policy is for NEW input.** Existing accounts are never rewritten or disabled for
  being non-ASCII; if any exist, the script stops and you decide what to do with them.
* **Enumeration is reduced, not eliminated:** while login identifies users globally by email, a
  signed-in owner can still tell "usable" from "unusable". The uniform answer and the rate limit make
  probing slow, uniform and visible. Removing it entirely needs an invitation/verification flow (no
  email infrastructure exists yet).
* **International users:** addresses with a non-ASCII local part cannot register at all, and
  international domains must be entered as Punycode. This is a deliberate, documented trade-off.
