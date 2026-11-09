package com.nexzian;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;

import com.jayway.jsonpath.JsonPath;
import com.nexzian.config.PlatformAdminBootstrap;
import com.nexzian.dto.AgencyRegistrationRequest;
import com.nexzian.dto.TeamMemberRequest;
import com.nexzian.entity.User;
import com.nexzian.exception.EmailUnavailableException;
import com.nexzian.security.CustomUserDetailsService;
import com.nexzian.service.AgencyRegistrationService;
import com.nexzian.service.UserService;

/**
 * R4: one canonical email per person, across every agency.
 *
 * Login finds people by email globally, so "Owner@X.com" and "owner@x.com" must be ONE identity
 * and a taken address must not leak which agency (if any) holds it. These tests protect:
 *  - canonical storage (lowercase, trimmed) for team members, owners and agencies;
 *  - login and user-details loading working with any casing, and JWT lookups staying consistent;
 *  - a single generic 409 for a taken address - same body whatever the cause - and never a 500;
 *  - the database being the real guard against concurrent, differently-cased duplicates;
 *  - the CHECK constraint refusing non-canonical direct writes;
 *  - the platform admin bootstrap being idempotent with an upper-case configured address.
 * Fixtures come from AuthorizationTestSupport (agency A with ownerA/agentA/staffA, agency B with
 * ownerB/agentB, a platform admin; every fixture email is lowercase).
 */
class EmailIdentityTests extends AuthorizationTestSupport {

    private static final String MEMBER_PASSWORD = "Str0ng-password";

    @Autowired private UserService userService;
    @Autowired private AgencyRegistrationService agencyRegistrationService;
    @Autowired private CustomUserDetailsService userDetailsService;
    @Autowired private JdbcTemplate jdbcTemplate;

    // ---- canonical storage -------------------------------------------------------------------------------

    @Test
    void teamMemberEmailIsStoredLowercase() throws Exception {
        post("/api/v1/users/team", ownerAToken, member("New.Hire@EXAMPLE.com"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new.hire@example.com"));

        User stored = userRepository.findByEmail("new.hire@example.com").orElseThrow();
        assertEquals(tenantA.getId(), stored.getTenantId(), "the member belongs to the caller's agency");
        assertEquals("AGENT", stored.getRole());
    }

    @Test
    void serviceTrimsPaddedEmailsEvenThoughRequestValidationAlsoRejectsThem() throws Exception {
        // Over HTTP the @Email validator already refuses padded input (400)...
        post("/api/v1/users/team", ownerAToken, member(" padded@example.com "))
                .andExpect(status().isBadRequest());

        // ...and the service is independently safe if it is ever called with padded input.
        userService.createTeamMember(tenantA.getId(), memberRequest(" Padded.Hire@Example.COM "));

        assertTrue(userRepository.findByEmail("padded.hire@example.com").isPresent());
    }

    @Test
    void agencyRegistrationStoresTheOwnerAndTheAgencyEmailLowercase() throws Exception {
        agencyRegistrationService.registerAgency(
                registration("Canonical Realty", "Boss@Canonical.EXAMPLE"));

        assertTrue(userRepository.findByEmail("boss@canonical.example").isPresent());
        assertTrue(tenantRepository.findByEmail("boss@canonical.example").isPresent());
        assertFalse(userRepository.existsByEmail("Boss@Canonical.EXAMPLE"),
                "no mixed-case spelling may exist in storage");
    }

    // ---- login and authentication lookups ----------------------------------------------------------------------

    @Test
    void loginWorksWithAnyCasingOfTheStoredEmail() throws Exception {
        for (String typed : List.of("agent-a@example.com", "Agent-A@Example.com", "AGENT-A@EXAMPLE.COM")) {
            post("/api/v1/auth/login", null, "{\"email\":\"" + typed + "\",\"password\":\"" + PASSWORD + "\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("agent-a@example.com"))
                    .andExpect(jsonPath("$.role").value("AGENT"));
        }
    }

    @Test
    void aTeamMemberCanLogInWithTheCasingTheOwnerTypedOrAnyOther() throws Exception {
        post("/api/v1/users/team", ownerAToken, member("Casing.Agent@Example.com")).andExpect(status().isCreated());

        post("/api/v1/auth/login", null,
                "{\"email\":\"CASING.AGENT@example.COM\",\"password\":\"" + MEMBER_PASSWORD + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("casing.agent@example.com"));
    }

    @Test
    void userDetailsLoadingUsesTheSameNormalization() {
        // The JWT filter calls this with the token's subject. Stored subjects are canonical, so
        // existing tokens keep working; any casing also resolves to the same single account.
        UserDetails exact = userDetailsService.loadUserByUsername("owner-a@example.com");
        UserDetails shouted = userDetailsService.loadUserByUsername("OWNER-A@EXAMPLE.COM");

        assertEquals("owner-a@example.com", exact.getUsername());
        assertEquals(exact.getUsername(), shouted.getUsername());
    }

    @Test
    void anExistingTokenForAStoredLowercaseEmailStillAuthenticates() throws Exception {
        // ownerAToken's subject is the lowercase stored email, exactly what tokens issued before
        // this change contain.
        get("/api/v1/users", ownerAToken).andExpect(status().isOk());
    }

    // ---- generic duplicate answer ------------------------------------------------------------------------------

    @Test
    void everyKindOfTakenAddressGetsTheIdenticalGeneric409() throws Exception {
        // Same agency, another agency, a platform account, and case variants of each. The answer
        // must not depend on WHERE the address lives or how it is spelled.
        List<String> takenAddresses = List.of(
                "agent-a@example.com",        // this agency, exact
                "AGENT-A@Example.com",        // this agency, case variant
                "owner-b@example.com",        // another agency, exact
                "Owner-B@EXAMPLE.COM",        // another agency, case variant
                "admin@example.com",          // platform administrator
                "ADMIN@example.com");
        long usersBefore = userRepository.count();

        Set<String> distinctBodies = new HashSet<>();
        for (String address : takenAddresses) {
            String body = post("/api/v1/users/team", ownerAToken, member(address))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(EmailUnavailableException.MESSAGE))
                    .andExpect(content().string(not(containsString("agency"))))
                    .andExpect(content().string(not(containsString(address))))
                    .andReturn().getResponse().getContentAsString();
            distinctBodies.add(JsonPath.read(body, "$.message") + "|" + JsonPath.read(body, "$.status")
                    + "|" + JsonPath.read(body, "$.error"));
        }

        assertEquals(1, distinctBodies.size(), "all taken-address answers must be indistinguishable");
        assertEquals(usersBefore, userRepository.count(), "nothing may be created");
    }

    @Test
    void aCaseVariantOfAnExistingAddressNeverBecomesAnInternalError() throws Exception {
        // Before R4 the duplicate check was case-sensitive, so a case variant slipped past it and
        // crashed on the unique constraint (HTTP 500) - which also revealed the address existed.
        for (String variant : List.of("Owner-B@example.com", "oWNER-b@example.com", "OWNER-B@EXAMPLE.COM")) {
            // MockMvc rethrows unhandled exceptions, so reaching andExpect already means "no 500".
            post("/api/v1/users/team", ownerAToken, member(variant)).andExpect(status().isConflict());
        }
    }

    // ---- concurrency: the database decides ---------------------------------------------------------------------

    @Test
    void ofManyConcurrentTeamCreationsWithDifferentCasingExactlyOneAccountExists() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 5; round++) {
                String base = "race.member" + round + "@example.com";
                List<Callable<Object>> calls = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    String spelling = i % 2 == 0 ? base.toUpperCase() : "Race.Member" + round + "@Example.com";
                    calls.add(() -> createOrCapture(() ->
                            userService.createTeamMember(tenantA.getId(), memberRequest(spelling))));
                }

                List<Object> outcomes = raceAtTheSameInstant(pool, calls);

                long created = outcomes.stream().filter(o -> o instanceof com.nexzian.dto.UserResponse).count();
                assertEquals(1, created, "exactly one request may create the account (round " + round + ")");
                outcomes.stream().filter(o -> !(o instanceof com.nexzian.dto.UserResponse)).forEach(loser ->
                        assertInstanceOf(EmailUnavailableException.class, loser,
                                "every loser must get the generic conflict, never another error"));
                assertEquals(1, countIgnoringCase(base), "one account, whatever the spelling");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void ofConcurrentRegistrationsWithDifferentCasingExactlyOneAgencyAndOwnerExist() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            long tenantsBefore = tenantRepository.count();
            List<Callable<Object>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int n = i;
                String spelling = n % 2 == 0 ? "Founder@Race.EXAMPLE" : "founder@race.example";
                calls.add(() -> createOrCapture(() -> {
                    agencyRegistrationService.registerAgency(registration("Race Agency " + n, spelling));
                    return Boolean.TRUE;
                }));
            }

            List<Object> outcomes = raceAtTheSameInstant(pool, calls);

            assertEquals(1, outcomes.stream().filter(o -> Boolean.TRUE.equals(o)).count(),
                    "exactly one registration may win");
            outcomes.stream().filter(o -> !Boolean.TRUE.equals(o)).forEach(loser ->
                    assertInstanceOf(IllegalArgumentException.class, loser,
                            "losers are reported as ordinary duplicates (HTTP 409), never as server errors"));
            assertEquals(1, countIgnoringCase("founder@race.example"));
            assertEquals(tenantsBefore + 1, tenantRepository.count(),
                    "losing registrations must roll back completely - no orphan agency");
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- the database is the last line of defence ---------------------------------------------------------------

    @Test
    void theDatabaseRefusesNonCanonicalEmailsWrittenDirectly() {
        // Why: entity setters and services normalize, but a bug, a script or a manual UPDATE can
        // bypass them. The CHECK constraint (email = lower(trim(email))) must still refuse the
        // write, otherwise case-variant duplicates could silently re-enter the table.
        UUID userId = ownerB.getId();
        UUID tenantId = tenantB.getId();

        for (String bad : List.of("Owner-B@Example.COM", " owner-b@example.com", "owner-b@example.com ")) {
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbcTemplate.update("update users set email = ? where id = ?", bad, userId),
                    "users must refuse '" + bad + "'");
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbcTemplate.update("update tenants set email = ? where id = ?", bad, tenantId),
                    "tenants must refuse '" + bad + "'");
        }
        assertEquals("owner-b@example.com", userRepository.findById(userId).orElseThrow().getEmail());

        // Control: a canonical value is accepted, so the constraint is not simply rejecting everything.
        assertEquals(1, jdbcTemplate.update("update users set email = ? where id = ?", "owner-b2@example.com", userId));
    }

    @Test
    void theDatabaseRefusesNonAsciiAndWhitespaceEmailsWrittenDirectly() {
        // Why: the supported-address policy (printable ASCII only) must also hold when something
        // bypasses the application - otherwise a look-alike or composed/decomposed duplicate could
        // be written directly. H2 runs the PORTABLE spelling of the rule (EmailConstraints.ASCII_PORTABLE),
        // which rejects non-ASCII, the space, EVERY control character and DEL, so those are all tested
        // here. PostgreSQL gets the regex spelling instead (EmailConstraintConsistencyTests proves both
        // accept exactly the same characters; GeneratedSchemaChecksTests proves which one each dialect
        // receives). Neither has been executed on PostgreSQL itself.
        UUID userId = ownerB.getId();
        UUID tenantId = tenantB.getId();

        List<String> unsupported = List.of(
                "caf\u00E9@example.com",            // non-ASCII local part
                "user@b\u00FCcher.de",              // Unicode domain
                "cafe\u0301@example.com",           // combining mark (decomposed form)
                "a b@example.com",                  // inner space
                "a\tb@example.com",                 // TAB
                "a\nb@example.com",                 // LF
                "a\u000bb@example.com",             // vertical tab
                "a\fb@example.com",                 // form feed
                "a\rb@example.com",                 // CR
                "a\u0001b@example.com",            // SOH, a low control character
                "a\u001fb@example.com",            // unit separator, the last low control character
                "a\u007fb@example.com");           // DEL, 0x7F
        for (String bad : unsupported) {
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbcTemplate.update("update users set email = ? where id = ?", bad, userId),
                    "users must refuse " + bad.codePoints().mapToObj(Integer::toHexString).toList());
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbcTemplate.update("update tenants set email = ? where id = ?", bad, tenantId),
                    "tenants must refuse " + bad.codePoints().mapToObj(Integer::toHexString).toList());
        }

        // Punycode is ASCII and therefore allowed by the database.
        assertEquals(1, jdbcTemplate.update("update users set email = ? where id = ?", "user@xn--bcher-kva.de", userId));
        assertEquals("beta-homes@tenant.example", tenantRepository.findById(tenantId).orElseThrow().getEmail(),
                "the tenant row must be untouched by the refused writes");
    }

    @Test
    void theUniqueConstraintAndTheCheckTogetherMakeUniquenessCaseInsensitive() {
        // A direct attempt to create a second account for the same address, spelled differently,
        // fails: the mixed-case spelling violates the CHECK, and the canonical spelling violates
        // the unique constraint. There is no spelling that gets in.
        for (String spelling : List.of("Agent-A@Example.com", "agent-a@example.com")) {
            assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                    "insert into users (id, tenant_id, full_name, email, password, role, status, created_at) "
                            + "values (?, ?, 'X', ?, 'hash', 'AGENT', 'ACTIVE', current_timestamp)",
                    UUID.randomUUID(), tenantB.getId(), spelling));
        }
    }

    // ---- platform admin bootstrap -------------------------------------------------------------------------------------

    @Test
    void platformAdminBootstrapIsIdempotentWhenTheConfiguredEmailHasUpperCase() throws Exception {
        // Before R4 the raw configured value was checked but a lowercased copy was saved, so on the
        // SECOND start the check missed, the insert hit the unique constraint, and start-up failed.
        var runner = new PlatformAdminBootstrap().createPlatformAdmin(
                userRepository, passwordEncoder, " Root.Admin@Nexzian.EXAMPLE ", "Bootstrap-pass-1");

        runner.run(null);
        runner.run(null);   // a second application start
        new PlatformAdminBootstrap().createPlatformAdmin(
                userRepository, passwordEncoder, "ROOT.ADMIN@nexzian.example", "Bootstrap-pass-1").run(null);

        assertEquals(1, countIgnoringCase("root.admin@nexzian.example"));
        User admin = userRepository.findByEmail("root.admin@nexzian.example").orElseThrow();
        assertEquals("PLATFORM_ADMIN", admin.getRole());
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------

    private String member(String email) {
        return "{\"fullName\":\"New Member\",\"email\":\"" + email + "\",\"password\":\""
                + MEMBER_PASSWORD + "\",\"role\":\"AGENT\"}";
    }

    private TeamMemberRequest memberRequest(String email) {
        TeamMemberRequest request = new TeamMemberRequest();
        request.setFullName("New Member");
        request.setEmail(email);
        request.setPassword(MEMBER_PASSWORD);
        request.setRole("AGENT");
        return request;
    }

    private AgencyRegistrationRequest registration(String businessName, String email) {
        AgencyRegistrationRequest request = new AgencyRegistrationRequest();
        request.setBusinessName(businessName);
        request.setFullName("Founder");
        request.setEmail(email);
        request.setPassword(MEMBER_PASSWORD);
        request.setCity("Hyderabad");
        return request;
    }

    private long countIgnoringCase(String email) {
        return userRepository.findAll().stream().filter(u -> u.getEmail().equalsIgnoreCase(email)).count();
    }

    /** Runs the action and returns its result, or the exception it threw, so threads never lose an outcome. */
    private Object createOrCapture(Callable<Object> action) {
        try {
            return action.call();
        } catch (Exception e) {
            return e;
        }
    }

    /** Releases all callers at the same instant so they genuinely overlap at the database. */
    private List<Object> raceAtTheSameInstant(ExecutorService pool, List<Callable<Object>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> call : calls) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return call.call();
            }));
        }
        assertTrue(ready.await(20, TimeUnit.SECONDS));
        go.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(60, TimeUnit.SECONDS));
        }
        return results;
    }
}
