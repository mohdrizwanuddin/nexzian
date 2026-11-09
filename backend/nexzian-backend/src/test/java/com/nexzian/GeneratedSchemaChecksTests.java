package com.nexzian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;

import com.nexzian.entity.EmailConstraints;
import com.nexzian.entity.Tenant;
import com.nexzian.entity.User;

/**
 * Proves WHICH email CHECK constraints Hibernate emits for each database, by generating the
 * schema-creation script as TEXT. No database is contacted: the dialect is chosen explicitly and
 * JDBC metadata access is switched off, so the script is generated purely from the entity
 * annotations.
 *
 * What this protects against: the entities declare a portable ASCII rule (used by H2) and a
 * {@code @DialectOverride} with the exact PostgreSQL regex. If that override were dropped or
 * mis-specified, a FRESH PostgreSQL database created through Hibernate would silently get only the
 * weaker portable rule, while a migrated database would get the full one. These tests fail in that
 * case. (Existing PostgreSQL databases are not created by Hibernate; they get the same constraint
 * from docs/r4-email-integrity-migration.sql - see MigrationScriptConsistencyTests.)
 *
 * What it does NOT prove: that PostgreSQL accepts the generated statement. That still needs a
 * rehearsal on a real PostgreSQL instance.
 */
class GeneratedSchemaChecksTests {

    @Test
    void aFreshPostgreSqlSchemaGetsTheExactPrintableAsciiRegexOnBothTables() throws IOException {
        String ddl = generate("org.hibernate.dialect.PostgreSQLDialect");

        for (String table : new String[] { "users", "tenants" }) {
            String createTable = createTableStatement(ddl, table);

            // The exact production rule, not the portable fallback.
            String ascii = checkExpression(createTable, table + "_email_ascii_chk");
            assertEquals(EmailConstraints.ASCII_POSTGRESQL, ascii,
                    table + ": a fresh PostgreSQL schema must carry the full regex rule");
            assertEquals("email ~ '^[!-~]+$'", ascii);
            assertFalse(createTable.contains("position(chr("),
                    table + ": the portable fallback must not leak into PostgreSQL DDL");

            // The canonical rule is still there, and nothing was duplicated or lost by the override.
            assertEquals(EmailConstraints.CANONICAL, checkExpression(createTable, table + "_email_canonical_chk"));
            assertEquals(1, occurrences(createTable, table + "_email_ascii_chk"));
            assertEquals(1, occurrences(createTable, table + "_email_canonical_chk"));
        }
    }

    @Test
    void anH2TestSchemaGetsThePortableFormThatCoversTheSameCharacters() throws IOException {
        String ddl = generate("org.hibernate.dialect.H2Dialect");

        for (String table : new String[] { "users", "tenants" }) {
            String createTable = createTableStatement(ddl, table);

            assertEquals(EmailConstraints.ASCII_PORTABLE, checkExpression(createTable, table + "_email_ascii_chk"),
                    table + ": H2 cannot use the PostgreSQL regex operator, so it gets the portable equivalent");
            assertFalse(createTable.contains("email ~"), "the PostgreSQL-only operator must not reach H2 DDL");
            assertEquals(EmailConstraints.CANONICAL, checkExpression(createTable, table + "_email_canonical_chk"));
            assertEquals(1, occurrences(createTable, table + "_email_ascii_chk"));
        }
    }

    @Test
    void theEntitiesKeepExactlyOneClassLevelCheckSoThePostgreSqlOverrideIsHonoured() {
        // Why: Hibernate consults @DialectOverride.Check only when a class has a SINGLE @Check. A
        // second class-level @Check (the repeated/container form) makes Hibernate ignore the
        // override WITHOUT any error, and a fresh PostgreSQL schema would quietly get the weaker
        // portable expression. The generated-DDL test above would catch that too; this states the
        // structural rule directly so the failure message points at the cause.
        for (Class<?> entity : new Class<?>[] { User.class, Tenant.class }) {
            assertEquals(1, entity.getAnnotationsByType(org.hibernate.annotations.Check.class).length,
                    entity.getSimpleName() + " must declare exactly one class-level @Check (the ASCII rule)");

            var overrides = entity.getAnnotationsByType(org.hibernate.annotations.DialectOverride.Check.class);
            assertEquals(1, overrides.length);
            assertEquals(org.hibernate.dialect.PostgreSQLDialect.class, overrides[0].dialect());
            assertEquals(EmailConstraints.ASCII_POSTGRESQL, overrides[0].override().constraints());
            // Same constraint name as the portable one it replaces, so exactly one is generated.
            assertEquals(entity.getAnnotationsByType(org.hibernate.annotations.Check.class)[0].name(),
                    overrides[0].override().name());
        }
    }

    @Test
    void thePortableFormCoversEveryControlCharacterIncludingDelAndTheSpace() {
        // 0x01-0x20 (controls and space) and 0x7F (DEL) each have a position() term; 0x00 is
        // omitted because PostgreSQL text cannot contain NUL. Non-ASCII is covered by the
        // octet_length = char_length comparison.
        String portable = EmailConstraints.ASCII_PORTABLE;
        assertTrue(portable.startsWith("octet_length(email) = char_length(email)"));
        for (int c = 1; c <= 32; c++) {
            assertTrue(portable.contains("position(chr(" + c + ") in email) = 0"), "missing control/space " + c);
        }
        assertTrue(portable.contains("position(chr(127) in email) = 0"), "DEL must be covered");
        assertEquals(33, occurrences(portable, "position(chr("), "33 terms: 1..32 and 127");
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /**
     * Generates the schema-creation script for User and Tenant as text, with NO database connection.
     * Uses the standard JPA script-generation settings: scripts are written, the database action is
     * "none", and JDBC metadata access is off so Hibernate does not try to connect to detect the
     * dialect (it is given explicitly).
     */
    private static String generate(String dialectClass) throws IOException {
        Path script = Files.createTempFile("nexzian-schema-", ".sql");
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", dialectClass)
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .applySetting("jakarta.persistence.schema-generation.database.action", "none")
                .applySetting("jakarta.persistence.schema-generation.scripts.action", "create")
                .applySetting("jakarta.persistence.schema-generation.scripts.create-target", script.toUri().toString())
                .build();
        try {
            Metadata metadata = new MetadataSources(registry)
                    .addAnnotatedClass(User.class)
                    .addAnnotatedClass(Tenant.class)
                    .buildMetadata();
            // Building the session factory is what triggers script generation.
            metadata.buildSessionFactory().close();
            return Files.readString(script, StandardCharsets.UTF_8);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
            Files.deleteIfExists(script);
        }
    }


    /** The full "create table <name> (...)" statement for one table. */
    private static String createTableStatement(String ddl, String table) {
        Matcher m = Pattern.compile("create table " + table + " \\(.*?\\);", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(ddl);
        assertTrue(m.find(), "no create table statement for " + table + " in:\n" + ddl);
        return m.group();
    }

    /** The expression of "constraint <name> check (<expression>)" inside a create table statement. */
    private static String checkExpression(String createTable, String constraintName) {
        int start = createTable.indexOf("constraint " + constraintName + " check (");
        assertTrue(start >= 0, "constraint " + constraintName + " missing in:\n" + createTable);
        int open = createTable.indexOf('(', start);
        int depth = 0;
        for (int i = open; i < createTable.length(); i++) {
            char c = createTable.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return createTable.substring(open + 1, i).trim();
                }
            }
        }
        throw new AssertionError("unbalanced parentheses for " + constraintName);
    }

    private static int occurrences(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
