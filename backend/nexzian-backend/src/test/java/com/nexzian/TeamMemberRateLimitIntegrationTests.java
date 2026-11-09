package com.nexzian;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP behaviour of the per-agency limit on POST /api/v1/users/team (default 30 attempts per hour).
 *
 * The endpoint answers "can this email be used?", so unthrottled it is an account-enumeration
 * tool. These tests protect: the limit counts FAILED attempts as well as successful ones, applies
 * before any email lookup, answers 429 with a usable Retry-After, is per agency, and is not
 * consumed by requests that never reach the controller (validation failures, unauthorized roles).
 * Window expiry is covered with a controllable clock in TeamMemberCreationRateLimiterTests.
 * Counters are cleared before every test by AuthorizationTestSupport.
 */
class TeamMemberRateLimitIntegrationTests extends AuthorizationTestSupport {

    private static final int LIMIT = 30;

    @Test
    void failedAndSuccessfulAttemptsBothCountAndTheNextOneIs429WithRetryAfter() throws Exception {
        // 10 successes + 20 duplicate-email failures = 30 valid attempts.
        for (int i = 0; i < 10; i++) {
            post("/api/v1/users/team", ownerAToken, member("limit.ok" + i + "@example.com"))
                    .andExpect(status().isCreated());
        }
        for (int i = 0; i < LIMIT - 10; i++) {
            post("/api/v1/users/team", ownerAToken, member("agent-a@example.com"))
                    .andExpect(status().isConflict());
        }

        // Even a brand-new, perfectly valid address is refused now: the limiter runs BEFORE any
        // email lookup, so a locked agency can neither create members nor learn anything.
        MvcResult blocked = post("/api/v1/users/team", ownerAToken, member("never.created@example.com"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber())
                .andExpect(jsonPath("$.message").value(containsString("Too many attempts to add team members")))
                .andReturn();

        long retryAfter = Long.parseLong(blocked.getResponse().getHeader("Retry-After"));
        assertTrue(retryAfter > 3500 && retryAfter <= 3600,
                "Retry-After should be about the one-hour window but was " + retryAfter);
        assertTrue(userRepository.findByEmail("never.created@example.com").isEmpty());
    }

    @Test
    void whileLockedEvenTakenAndFreeAddressesGetTheSame429() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            post("/api/v1/users/team", ownerAToken, member("agent-a@example.com")).andExpect(status().isConflict());
        }

        // A taken address (409 before) and a free one (201 before) are indistinguishable now.
        post("/api/v1/users/team", ownerAToken, member("owner-b@example.com")).andExpect(status().isTooManyRequests());
        post("/api/v1/users/team", ownerAToken, member("totally.free@example.com")).andExpect(status().isTooManyRequests());
    }

    @Test
    void oneAgencyBeingLimitedDoesNotAffectAnother() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            post("/api/v1/users/team", ownerAToken, member("agent-a@example.com")).andExpect(status().isConflict());
        }
        post("/api/v1/users/team", ownerAToken, member("another@example.com")).andExpect(status().isTooManyRequests());

        post("/api/v1/users/team", ownerBToken, member("b.hire@example.com"))
                .andExpect(status().isCreated());
        assertEquals(tenantB.getId(), userRepository.findByEmail("b.hire@example.com").orElseThrow().getTenantId());
    }

    @Test
    void validationFailuresAreNotCounted() throws Exception {
        // These are rejected (400) before the controller runs, so they cannot be used to burn an
        // agency's allowance, and a typo does not cost a real attempt.
        for (int i = 0; i < LIMIT + 5; i++) {
            post("/api/v1/users/team", ownerAToken, member("not-an-email")).andExpect(status().isBadRequest());
        }

        post("/api/v1/users/team", ownerAToken, member("still.allowed@example.com")).andExpect(status().isCreated());
    }

    @Test
    void requestsFromUnauthorizedRolesNeverConsumeTheOwnersAllowance() throws Exception {
        // AGENT / STAFF / anonymous callers are refused by authorization before the controller, so
        // they can neither be limited themselves nor exhaust the owner's budget.
        for (int i = 0; i < LIMIT + 5; i++) {
            post("/api/v1/users/team", agentAToken, member("x" + i + "@example.com")).andExpect(status().isForbidden());
            post("/api/v1/users/team", staffAToken, member("y" + i + "@example.com")).andExpect(status().isForbidden());
        }
        post("/api/v1/users/team", null, member("anon@example.com")).andExpect(status().isUnauthorized());

        post("/api/v1/users/team", ownerAToken, member("owner.can.still@example.com")).andExpect(status().isCreated());
    }

    @Test
    void theLimiterIsKeyedByTheAuthenticatedAgencyNotByAnythingTheClientSends() throws Exception {
        // A forged tenant id in the body and forged forwarding headers must not move the counter
        // to another agency (or escape the limit): the key is the caller's own tenant.
        String forged = "{\"fullName\":\"X\",\"email\":\"agent-a@example.com\",\"password\":\"Str0ng-password\","
                + "\"role\":\"AGENT\",\"tenantId\":\"" + tenantB.getId() + "\"}";
        for (int i = 0; i < LIMIT; i++) {
            call(org.springframework.http.HttpMethod.POST, "/api/v1/users/team", ownerAToken, forged)
                    .andExpect(status().isConflict());
        }

        post("/api/v1/users/team", ownerAToken, member("late@example.com")).andExpect(status().isTooManyRequests());
        // Agency B's allowance is untouched by A's requests that named B.
        post("/api/v1/users/team", ownerBToken, member("b.fresh@example.com")).andExpect(status().isCreated());
    }

    private String member(String email) {
        return "{\"fullName\":\"New Member\",\"email\":\"" + email + "\",\"password\":\"Str0ng-password\","
                + "\"role\":\"AGENT\"}";
    }
}
