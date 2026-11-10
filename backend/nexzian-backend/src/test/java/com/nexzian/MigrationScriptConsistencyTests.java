package com.nexzian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.hibernate.annotations.Check;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nexzian.entity.EmailConstraints;
import com.nexzian.entity.Tenant;
import com.nexzian.entity.User;
import com.nexzian.util.EmailNormalizer;

/**
 * STATIC REVIEW of docs/r4-email-integrity-migration.sql and its runbook.
 *
 * IMPORTANT: these tests read the script as TEXT. They do NOT execute it and do NOT prove it works
 * on PostgreSQL - the test database is H2, which cannot run these statements. What they do guard
 * against is the script silently drifting away from the rest of the system:
 *  - the constraint names/expressions in the script match the entity @Check annotations;
 *  - the PostgreSQL regex the script installs accepts and rejects exactly what the Java policy
 *    (EmailNormalizer.findProblem) accepts and rejects, character by character;
 *  - the script's safety promises still hold in its text: no deletes, locale-independent rewrite,
 *    one transaction, enforcing guards, a self-rolling-back probe, no automatic backup creation.
 * The script itself must still be rehearsed on a copy of the real database (see the runbook).
 */
class MigrationScriptConsistencyTests {

    private static String sql;
    private static String activeSql;      // the script with comment lines removed
    private static String runbook;

    @BeforeAll
    static void readDocs() throws IOException {
        Path sqlPath = Path.of("docs", "r4-email-integrity-migration.sql");
        Path mdPath = Path.of("docs", "r4-email-integrity-migration.md");
        assertTrue(Files.exists(sqlPath), "run from the project directory; missing " + sqlPath.toAbsolutePath());
        sql = Files.readString(sqlPath, StandardCharsets.UTF_8);
        runbook = Files.readString(mdPath, StandardCharsets.UTF_8);
        activeSql = Arrays.stream(sql.split("\\R"))
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(Collectors.joining("\n"));
    }

    // ---- consistency with the entities and with EmailConstraints ------------------------------------

    /** Every constraint the entities declare: the class-level ASCII check and the column-level canonical check. */
    private static List<Check> declaredChecks(Class<?> entity) throws NoSuchFieldException {
        List<Check> checks = new ArrayList<>(Arrays.asList(entity.getAnnotationsByType(Check.class)));
        checks.addAll(Arrays.asList(entity.getDeclaredField("email").getAnnotationsByType(Check.class)));
        return checks;
    }

    @Test
    void everyEntityCheckNameAppearsInTheScript() throws Exception {
        for (Class<?> entity : List.of(User.class, Tenant.class)) {
            List<Check> checks = declaredChecks(entity);
            assertEquals(2, checks.size(), entity.getSimpleName() + " should declare the canonical and ascii checks");
            for (Check check : checks) {
                assertTrue(sql.contains(check.name()), "script must manage constraint " + check.name());
            }
        }
    }

    @Test
    void theScriptInstallsTheSameExpressionsAsTheEntitiesAndEmailConstraints() throws Exception {
        // Canonical rule: identical text in the entities, the shared constants and the script.
        // ASCII rule: the script installs ASCII_POSTGRESQL - the very expression Hibernate puts on a
        // FRESH PostgreSQL schema - so fresh and migrated databases end up with the same constraint.
        for (Class<?> entity : List.of(User.class, Tenant.class)) {
            String canonical = declaredChecks(entity).stream()
                    .filter(c -> c.name().endsWith("canonical_chk")).findFirst().orElseThrow().constraints();
            assertEquals(EmailConstraints.CANONICAL, canonical);
            assertTrue(sql.contains("CHECK (" + canonical + ")"),
                    "script must install the canonical expression of " + entity.getSimpleName() + ": " + canonical);

            var override = entity.getAnnotationsByType(org.hibernate.annotations.DialectOverride.Check.class)[0]
                    .override().constraints();
            assertEquals(EmailConstraints.ASCII_POSTGRESQL, override);
            assertTrue(sql.contains("CHECK (" + override + ")"),
                    "script must install the PostgreSQL ASCII expression Hibernate uses for " + entity.getSimpleName());
        }
    }


    // ---- the PostgreSQL ASCII regex agrees with the Java policy ----------------------------------------

    @Test
    void thePostgresAsciiRegexAcceptsExactlyWhatTheJavaPolicyAccepts() {
        Matcher installed = Pattern.compile("CHECK \\(email ~ '([^']+)'\\)").matcher(activeSql);
        assertTrue(installed.find(), "script must install the ASCII CHECK as: CHECK (email ~ '<regex>')");
        String regex = installed.group(1);
        assertEquals("^[!-~]+$", regex, "the documented rule is printable ASCII 0x21-0x7E, one or more characters");

        // PostgreSQL's ~ is a "contains a match" operator, and its "$" matches only at the very end
        // of the string. Java's full-string matches() is the equivalent for this simple class
        // (find() would wrongly let "$" match before a trailing newline).
        Pattern pattern = Pattern.compile(regex);

        for (int c = 0; c <= 0x2FF; c++) {
            String candidate = "a" + new String(Character.toChars(c)) + "b@x.com";
            boolean javaSupports = EmailNormalizer.findProblem(candidate) == null;
            assertEquals(javaSupports, pattern.matcher(candidate).matches(),
                    String.format("policy and database regex disagree for U+%04X", c));
        }
        for (String sample : List.of("a@b.com", "user@xn--bcher-kva.de", "a b@x.com", "a\tb@x.com",
                "a\nb@x.com", "trailing@x.com\n", "\u7528\u6237@x.com", "a\u00A0b@x.com", "a\u007fb@x.com", "")) {
            assertEquals(EmailNormalizer.findProblem(sample) == null && !sample.isEmpty(),
                    pattern.matcher(sample).matches(), "mismatch for " + sample.codePoints().boxed().toList());
        }
    }

    // ---- the script's safety promises, as text --------------------------------------------------------------

    @Test
    void theScriptNeverDeletesOrDropsData() {
        String upper = activeSql.toUpperCase();
        assertFalse(upper.contains("DELETE FROM"), "no DELETE statements");
        assertFalse(upper.contains("TRUNCATE"), "no TRUNCATE");
        assertFalse(upper.contains("DROP TABLE"), "no DROP TABLE (backup cleanup is manual and commented)");
        // The only DROPs are of the script's own four constraints, and only IF EXISTS.
        Matcher drops = Pattern.compile("DROP\\s+(\\w+)", Pattern.CASE_INSENSITIVE).matcher(activeSql);
        while (drops.find()) {
            assertEquals("CONSTRAINT", drops.group(1).toUpperCase());
        }
        assertEquals(4, count("DROP CONSTRAINT IF EXISTS"));
    }

    @Test
    void theOnlyDataChangeIsALocaleIndependentAsciiLowercase() {
        String mapping = "translate(email, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')";
        Matcher updates = Pattern.compile("UPDATE\\s+(users|tenants)\\s+SET\\s+email\\s*=\\s*([^\\n]+)").matcher(activeSql);
        int found = 0;
        while (updates.find()) {
            found++;
            assertTrue(updates.group(2).contains(mapping),
                    "rewrite must use the explicit A-Z -> a-z mapping, not lower(): " + updates.group(0));
            assertFalse(updates.group(2).toLowerCase().contains("lower("), "rewrite must not use lower()");
        }
        // Two rewrites (users, tenants). The self-test probe's UPDATE is dynamic SQL inside a DO block.
        assertEquals(2, found);
    }

    @Test
    void theMigrationRunsInOneTransactionWithEnforcingGuards() {
        assertEquals(1, count("\nBEGIN;"), "exactly one explicit transaction");
        assertEquals(1, count("\nCOMMIT;"));
        assertTrue(activeSql.indexOf("LOCK TABLE") < activeSql.indexOf("$preflight$"),
                "guards must run AFTER the tables are locked, so data cannot change between check and change");
        assertTrue(activeSql.indexOf("$postcheck$") < activeSql.indexOf("COMMIT;"),
                "post-conditions must be verified BEFORE commit");
        assertTrue(activeSql.contains("SET LOCAL lock_timeout"));

        // Each condition that must stop the migration is a RAISE EXCEPTION in the guard block.
        String preflight = between("$preflight$", "$preflight$;");
        for (String mustGuard : List.of("not found via search_path", "no UNIQUE constraint", "contain whitespace",
                "differ only by letter case", "backup table")) {
            assertTrue(preflight.contains(mustGuard), "guard missing: " + mustGuard);
        }
        assertTrue(preflight.contains("contype = 'u'") && preflight.contains("conkey = ARRAY[v_attnum]"),
                "uniqueness must be verified through pg_constraint, by column");
    }

    @Test
    void theLocaleIsReadFromTheCatalogBecauseShowLcCtypeFailsOnPostgreSql18() {
        // Regression for a defect found by rehearsing the script on PostgreSQL 18.6: the statement
        // "SHOW lc_ctype" raised 'unrecognized configuration parameter "lc_ctype"' and (with
        // ON_ERROR_STOP) would have stopped the run in STEP 0. That run-time parameter is gone on
        // 18; the pg_database catalog columns work on every version. H2 and this static test cannot
        // execute SQL, so the test pins the TEXT: the catalog query must be present and the removed
        // parameter must never come back as an executable statement.
        String normalized = activeSql.replaceAll("\\s+", " ");
        assertTrue(normalized.contains(
                "SELECT datcollate, datctype FROM pg_database WHERE datname = current_database();"),
                "STEP 0 must display the locale from the pg_database catalog");
        assertFalse(Pattern.compile("(?i)\\bSHOW\\s+lc_(ctype|collate)\\b").matcher(activeSql).find(),
                "SHOW lc_ctype is not valid on PostgreSQL 18 (and lc_collate is avoided for the same reason): "
                        + "the locale must come from the catalog");
        // The reason is documented next to the statement and in the runbook, so nobody 'simplifies' it back.
        assertTrue(sql.contains("PostgreSQL 18") && sql.contains("unrecognized configuration parameter"));
        assertTrue(runbook.contains("datcollate") && runbook.contains("PostgreSQL 18"));
    }

    @Test
    void thePreChecksCoverWhitespaceNonAsciiAndSchema() {
        assertTrue(activeSql.contains("current_setting('search_path')") && activeSql.contains("current_schema()"),
                "the resolved schema must be displayed rather than assumed");
        assertTrue(activeSql.contains("email !~ '^[!-~]+$'"), "policy violations must be detected before any change");
        assertTrue(activeSql.contains("[^ -~]"), "non-ASCII/control detail report");
    }

    @Test
    void theSelfTestProbeCannotPersistATestValue() {
        String probe = between("$probe$", "$probe$;");
        assertTrue(probe.contains("EXCEPTION WHEN check_violation THEN"), "the expected violation is caught inside the block");
        assertTrue(probe.contains("RAISE EXCEPTION 'R4 probe FAILED"),
                "an unexpectedly accepted value raises, which aborts the DO statement and undoes the update");
        assertFalse(activeSql.matches("(?s).*\\bROLLBACK;.*"),
                "the old BEGIN ... UPDATE ... ROLLBACK pattern must not be used (unsafe in auto-commit clients)");
        for (String suffix : List.of("' '", "E'\\t'", "E'\\n'", "chr(233)", "'X'")) {
            assertTrue(probe.contains(suffix), "probe must try " + suffix);
        }
    }

    @Test
    void backupTablesAreNeverCreatedByTheScriptItself() {
        assertFalse(Pattern.compile("CREATE\\s+TABLE", Pattern.CASE_INSENSITIVE).matcher(activeSql).find(),
                "backup creation is documented in comments only; the script must not create tables");
        assertTrue(sql.contains("CREATE TABLE r4_backup_users_email") && sql.contains("CREATE TABLE r4_backup_tenants_email"),
                "the backup instructions must be documented in the pre-migration section");
        assertTrue(sql.indexOf("CREATE TABLE r4_backup_users_email") < sql.indexOf("STEP 1 -"),
                "backup instructions belong before any report or change");
    }

    @Test
    void theRunbookGivesTheRequiredInstructions() {
        assertTrue(runbook.contains("psql -v ON_ERROR_STOP=1 -f docs/r4-email-integrity-migration.sql"));
        assertTrue(runbook.toLowerCase().contains("rehearse"));
        assertTrue(runbook.contains("never been run against a PostgreSQL database"),
                "the runbook must not claim the SQL was executed");
        for (String section : List.of("## Schema assumptions", "## Procedure", "## Fresh database or existing database?",
                "## Transaction behaviour and safe aborts", "## Rollback")) {
            assertTrue(runbook.contains(section), "runbook section missing: " + section);
        }
    }

    @Test
    void theRunbookSeparatesFreshAndExistingDatabaseProcedures() {
        String section = runbook.substring(runbook.indexOf("## Fresh database or existing database?"),
                runbook.indexOf("## Transaction behaviour and safe aborts"));
        assertTrue(section.contains("Fresh PostgreSQL database") && section.contains("Existing PostgreSQL database"));
        assertTrue(section.contains("Hibernate") && section.contains("ddl-auto=update"));
        assertTrue(section.contains("never adds checks to a table that already exists"),
                "must warn that an application start does not add checks to existing tables");
        for (String name : List.of("users_email_canonical_chk", "users_email_ascii_chk",
                "tenants_email_canonical_chk", "tenants_email_ascii_chk")) {
            assertTrue(section.contains(name), "verification query must name " + name);
        }
        assertTrue(section.contains("pg_get_constraintdef"), "a verification query must be provided");
        assertTrue(sql.contains("FRESH vs EXISTING DATABASES"), "the script header must say which databases need it");
    }
    // ---- helpers ------------------------------------------------------------------------------------------------

    private static int count(String needle) {
        int n = 0;
        for (int i = activeSql.indexOf(needle); i >= 0; i = activeSql.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    /** Text between the first occurrence of {@code start} and the next occurrence of {@code end}. */
    private static String between(String start, String end) {
        int from = activeSql.indexOf(start);
        assertTrue(from >= 0, "missing " + start);
        int to = activeSql.indexOf(end, from + start.length());
        assertTrue(to > from, "missing " + end);
        assertNotNull(activeSql);
        return activeSql.substring(from, to);
    }
}
