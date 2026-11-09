package com.nexzian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.nexzian.config.PlatformAdminBootstrap;
import com.nexzian.dto.AgencyRegistrationRequest;
import com.nexzian.dto.TeamMemberRequest;
import com.nexzian.exception.UnsupportedEmailException;
import com.nexzian.repository.UserRepository;
import com.nexzian.service.AgencyRegistrationService;
import com.nexzian.service.UserService;

/**
 * R4: the supported-address policy - printable ASCII only, no whitespace or control characters, at
 * most 150 characters, international domains in Punycode - as seen through the two account-
 * creating endpoints and through internal service calls.
 *
 * What these protect against: Java and PostgreSQL only agree on lowercase for ASCII, and Unicode
 * lets one visible address exist as several accounts (composed/decomposed forms, Unicode vs
 * Punycode domain) or lets an attacker register a look-alike of someone else's address. The
 * policy is enforced by request validation (HTTP 400), by a safety net for callers that bypass
 * validation, and by database CHECK constraints (see EmailIdentityTests and
 * MigrationScriptConsistencyTests). LOGIN is deliberately not restricted.
 *
 * Request bodies are written with JSON escapes (\\t, \\n) so the server really receives those
 * characters.
 */
class AsciiEmailPolicyTests extends AuthorizationTestSupport {

    private static final String ASCII_ONLY_MESSAGE = "Use an email address with ASCII characters only.";

    @Autowired private UserService userService;
    @Autowired private AgencyRegistrationService agencyRegistrationService;

    // ---- acceptance -----------------------------------------------------------------------------------------------

    @Test
    void asciiAddressesAreAcceptedByBothEndpoints() throws Exception {
        post("/api/v1/users/team", ownerAToken, member("Plain.Ascii+tag@Example.COM"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("plain.ascii+tag@example.com"));

        post("/api/v1/auth/register-agency", null, registration("Ascii Realty", "Boss@Ascii.Example"))
                .andExpect(status().isCreated());
    }

    @Test
    void punycodeDomainsAreAcceptedBecauseTheyAreAscii() throws Exception {
        // Internationalized domains are supported in their ASCII (xn--) form.
        post("/api/v1/users/team", ownerAToken, member("user@xn--bcher-kva.de")).andExpect(status().isCreated());
        post("/api/v1/auth/register-agency", null, registration("Punycode Realty", "boss@xn--bcher-kva.de"))
                .andExpect(status().isCreated());
    }

    @Test
    void anAddressOfExactly150CharactersIsAcceptedAnd151IsRejected() throws Exception {
        // 64-char local part + "@" + 85-char domain = 150 (the column length). One more is refused.
        String domain = "d".repeat(63) + "." + "e".repeat(17) + ".com";
        String at150 = "a".repeat(64) + "@" + domain;
        String at151 = "a".repeat(64) + "@" + domain.replace("e".repeat(17), "e".repeat(18));
        assertEquals(150, at150.length());
        assertEquals(151, at151.length());

        post("/api/v1/users/team", ownerAToken, member(at150)).andExpect(status().isCreated());
        post("/api/v1/users/team", ownerAToken, member(at151))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages.email").exists());

        // A different 150-character address for registration (the one above is now taken by the
        // team member we just created, which would be a legitimate duplicate).
        String otherAt150 = "b".repeat(64) + "@" + domain;
        post("/api/v1/auth/register-agency", null, registration("Long Address Realty", otherAt150))
                .andExpect(status().isCreated());
        post("/api/v1/auth/register-agency", null, registration("Too Long Realty", at151))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages.email").exists());
    }

    // ---- rejection with the documented message ---------------------------------------------------------------

    @Test
    void nonAsciiLocalPartsAreRejectedWithTheDocumentedMessage() throws Exception {
        long usersBefore = userRepository.count();

        for (String bad : List.of("j\u00F6rg@example.com", "\u7528\u6237@example.com", "cafe\u0301@example.com")) {
            post("/api/v1/users/team", ownerAToken, member(bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messages.email").value(ASCII_ONLY_MESSAGE));
            post("/api/v1/auth/register-agency", null, registration("Unicode Local " + bad.hashCode(), bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messages.email").value(ASCII_ONLY_MESSAGE));
        }
        assertEquals(usersBefore, userRepository.count(), "nothing may be created");
    }

    @Test
    void unicodeDomainsAreRejectedAndMustBeSuppliedAsPunycode() throws Exception {
        for (String bad : List.of("user@b\u00FCcher.de", "user@\u4F8B\u5B50.\u5E7F\u544A")) {
            post("/api/v1/users/team", ownerAToken, member(bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messages.email").value(ASCII_ONLY_MESSAGE));
            post("/api/v1/auth/register-agency", null, registration("Unicode Domain " + bad.hashCode(), bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messages.email").value(ASCII_ONLY_MESSAGE));
        }
    }

    @Test
    void whitespaceTabsAndNewlinesAreRejectedByBothEndpoints() throws Exception {
        // JSON escapes: the server receives a real space / TAB / LF inside or around the address.
        long usersBefore = userRepository.count();
        for (String bad : List.of("sp ace@example.com", "tab\\t@example.com", "new\\nline@example.com",
                "\\tleading@example.com", "trailing@example.com\\n", " padded@example.com ")) {
            post("/api/v1/users/team", ownerAToken, member(bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messages.email").exists());
            post("/api/v1/auth/register-agency", null, registration("Whitespace " + bad.hashCode(), bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messages.email").exists());
        }
        assertEquals(usersBefore, userRepository.count());
    }

    @Test
    void rejectedAddressesDoNotConsumeTheTeamCreationAllowance() throws Exception {
        // 400s happen before the controller, so probing with unsupported input cannot burn the
        // agency's limit (nor does it reveal anything about existing accounts).
        for (int i = 0; i < 35; i++) {
            post("/api/v1/users/team", ownerAToken, member("j\u00F6rg" + i + "@example.com"))
                    .andExpect(status().isBadRequest());
        }
        post("/api/v1/users/team", ownerAToken, member("still.allowed@example.com")).andExpect(status().isCreated());
    }

    // ---- the policy cannot be bypassed by internal callers ----------------------------------------------------

    @Test
    void directServiceCallsCannotBypassThePolicy() {
        // Why: request validation only guards the HTTP layer. These callers skip it entirely.
        long usersBefore = userRepository.count();
        long tenantsBefore = tenantRepository.count();

        UnsupportedEmailException team = assertThrows(UnsupportedEmailException.class,
                () -> userService.createTeamMember(tenantA.getId(), memberRequest("j\u00F6rg@example.com")));
        assertEquals(ASCII_ONLY_MESSAGE, team.getMessage());
        assertThrows(UnsupportedEmailException.class,
                () -> userService.createTeamMember(tenantA.getId(), memberRequest("in ner@example.com")));

        assertThrows(UnsupportedEmailException.class,
                () -> agencyRegistrationService.registerAgency(registrationRequest("Bypass Realty", "b\u00FCcher@example.com")));

        assertEquals(usersBefore, userRepository.count());
        assertEquals(tenantsBefore, tenantRepository.count(), "a refused registration must leave no agency behind");
    }

    @Test
    void platformAdminBootstrapRefusesAnUnsupportedConfiguredAddressButToleratesAnExistingAdmin() throws Exception {
        // Creating from a non-ASCII configured address fails fast with the policy message...
        var runner = new PlatformAdminBootstrap().createPlatformAdmin(
                userRepository, passwordEncoder, "R\u00F6\u00F6t@Nexzian.EXAMPLE", "Bootstrap-pass-1");
        assertThrows(UnsupportedEmailException.class, () -> runner.run(null));

        // ...but an administrator that already exists never makes start-up fail, whatever the
        // configured spelling: existence is checked before the policy is applied.
        new PlatformAdminBootstrap().createPlatformAdmin(
                userRepository, passwordEncoder, "ADMIN@EXAMPLE.COM", "Bootstrap-pass-1").run(null);   // fixture admin exists
        assertEquals(1, userRepository.findAll().stream()
                .filter(u -> u.getEmail().equals("admin@example.com")).count());
    }

    @Test
    void anAdministratorAlreadyStoredUnderAnyAddressNeverMakesStartUpFail() throws Exception {
        // Why: the policy is applied only when CREATING the administrator. If one already exists
        // (even under an address the policy would now refuse - e.g. a legacy row on a database not
        // yet migrated), start-up must not fail and nothing is written. The database cannot hold
        // such a row once the CHECK exists, so the repository is mocked to simulate the legacy row.
        UserRepository repository = mock(UserRepository.class);
        when(repository.existsByEmail("r\u00F6\u00F6t@nexzian.example")).thenReturn(true);

        new PlatformAdminBootstrap().createPlatformAdmin(
                repository, passwordEncoder, "R\u00D6\u00D6T@Nexzian.EXAMPLE", "Bootstrap-pass-1").run(null);

        verify(repository, never()).save(any());
    }

    // ---- login stays compatible --------------------------------------------------------------------------------

    @Test
    void loginIsNotRestrictedByThePolicyAndStoredAccountsStillSignIn() throws Exception {
        // Existing accounts (stored ASCII, lowercase) keep working with any casing...
        post("/api/v1/auth/login", null, "{\"email\":\"OWNER-A@Example.com\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isOk());

        // ...and NON-ASCII input is not a new validation error: it is just "no such account", the
        // same generic 401 as any unknown address (so login reveals nothing about the policy).
        for (String attempt : List.of("j\u00F6rg@example.com", "user@b\u00FCcher.de", "\u7528\u6237@example.com")) {
            post("/api/v1/auth/login", null, "{\"email\":\"" + attempt + "\",\"password\":\"" + PASSWORD + "\"}")
                    .andExpect(status().isUnauthorized());
        }

        // Unchanged, pre-existing behaviour (not part of R4): the login request has always applied
        // the standard @Email check, so a plainly malformed value such as one containing a space is
        // a 400. R4 adds no login rule of its own.
        post("/api/v1/auth/login", null, "{\"email\":\"owner a@example.com\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isBadRequest());
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** JSON body; {@code email} is inserted as-is, so callers pass JSON escapes (\\t) where needed. */
    private String member(String email) {
        return "{\"fullName\":\"New Member\",\"email\":\"" + email + "\",\"password\":\"Str0ng-password\","
                + "\"role\":\"AGENT\"}";
    }

    private String registration(String businessName, String email) {
        return "{\"businessName\":\"" + businessName + "\",\"fullName\":\"Founder\",\"email\":\"" + email
                + "\",\"password\":\"Str0ng-password\",\"city\":\"Hyderabad\"}";
    }

    private TeamMemberRequest memberRequest(String email) {
        TeamMemberRequest request = new TeamMemberRequest();
        request.setFullName("New Member");
        request.setEmail(email);
        request.setPassword("Str0ng-password");
        request.setRole("AGENT");
        return request;
    }

    private AgencyRegistrationRequest registrationRequest(String businessName, String email) {
        AgencyRegistrationRequest request = new AgencyRegistrationRequest();
        request.setBusinessName(businessName);
        request.setFullName("Founder");
        request.setEmail(email);
        request.setPassword("Str0ng-password");
        request.setCity("Hyderabad");
        return request;
    }
}
