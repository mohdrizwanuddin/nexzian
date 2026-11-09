package com.nexzian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.nexzian.entity.EmailConstraints;
import com.nexzian.util.EmailNormalizer;

/**
 * Proves, character by character, that BOTH spellings of the database ASCII rule accept exactly
 * what the application policy (EmailNormalizer.findProblem) accepts, so the application, the H2
 * test schema and the PostgreSQL schema can never disagree about which addresses are valid:
 *
 *  - ASCII_PORTABLE (used by H2 and as the generic fallback) is EVALUATED on a real H2 engine;
 *  - ASCII_POSTGRESQL (the production regex) is applied with java.util.regex, using full-string
 *    matching because PostgreSQL's "$" matches only at the very end of the string.
 *
 * Limit: the PostgreSQL regex is checked with Java's regex engine, not PostgreSQL's. For this
 * single character class and anchors the two engines agree, but the statement has still not been
 * executed on PostgreSQL itself. Uses a private in-memory H2 database; no application data.
 */
class EmailConstraintConsistencyTests {

    /** Every character value worth testing: all of Latin-1 plus a spread of other scripts and an emoji. */
    private static List<String> corpus() {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (int c = 1; c <= 0x2FF; c++) {                       // 0 omitted: PostgreSQL text cannot hold NUL
            values.add("a" + new String(Character.toChars(c)) + "b@x.com");
        }
        for (int c : new int[] { 0x0370, 0x0416, 0x05D0, 0x0627, 0x3042, 0x4E2D, 0xFF21, 0x1F600 }) {
            values.add("a" + new String(Character.toChars(c)) + "b@x.com");
        }
        values.addAll(List.of("owner@example.com", "user@xn--bcher-kva.de", "o'brien+tag@example.co.uk",
                "a b@x.com", "trailing@x.com\n", "\tleading@x.com"));
        return values;
    }

    @Test
    void thePortableExpressionRunsOnH2AndAgreesWithTheApplicationPolicy() throws SQLException {
        String sql = "SELECT CASE WHEN (" + EmailConstraints.ASCII_PORTABLE + ") THEN 1 ELSE 0 END FROM (SELECT CAST(? AS VARCHAR(400)) AS email) t";
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:email-constraint-consistency;MODE=PostgreSQL");
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String candidate : corpus()) {
                statement.setString(1, candidate);
                try (ResultSet row = statement.executeQuery()) {
                    assertTrue(row.next());
                    boolean databaseAccepts = row.getInt(1) == 1;
                    boolean policyAccepts = EmailNormalizer.findProblem(candidate) == null;
                    assertEquals(policyAccepts, databaseAccepts,
                            "portable CHECK and policy disagree for " + describe(candidate));
                }
            }
        }
    }

    @Test
    void thePostgreSqlRegexAgreesWithTheApplicationPolicy() {
        Matcher expression = Pattern.compile("^email ~ '(.+)'$").matcher(EmailConstraints.ASCII_POSTGRESQL);
        assertTrue(expression.matches(), "ASCII_POSTGRESQL must have the form: email ~ '<regex>'");
        Pattern regex = Pattern.compile(expression.group(1));

        for (String candidate : corpus()) {
            assertEquals(EmailNormalizer.findProblem(candidate) == null, regex.matcher(candidate).matches(),
                    "PostgreSQL regex and policy disagree for " + describe(candidate));
        }
    }

    @Test
    void delAndEveryOtherControlCharacterAreRejectedByBothSpellings() throws SQLException {
        // The exact gap the fresh-database review found: the portable form must reject DEL (0x7F) and the
        // low control characters just like the PostgreSQL regex does.
        Pattern regex = Pattern.compile("^[!-~]+$");
        String sql = "SELECT CASE WHEN (" + EmailConstraints.ASCII_PORTABLE + ") THEN 1 ELSE 0 END FROM (SELECT CAST(? AS VARCHAR(400)) AS email) t";
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:email-constraint-controls;MODE=PostgreSQL");
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int control : new int[] { 1, 7, 8, 27, 31, 127 }) {
                String candidate = "a" + (char) control + "b@x.com";
                assertEquals(false, regex.matcher(candidate).matches(), "regex must reject " + control);
                statement.setString(1, candidate);
                try (ResultSet row = statement.executeQuery()) {
                    row.next();
                    assertEquals(0, row.getInt(1), "portable CHECK must reject control character " + control);
                }
            }
        }
    }

    private static String describe(String value) {
        return value.codePoints().mapToObj(c -> c < 0x21 || c > 0x7E ? String.format("\\u%04X", c) : String.valueOf((char) c))
                .reduce("", String::concat);
    }
}
