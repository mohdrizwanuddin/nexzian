package com.nexzian;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.nexzian.entity.User;

/** Team management across tenants and against the OWNER account. */
class CrossTenantTeamAccessTests extends AuthorizationTestSupport {

    private static String access(String role, String status) {
        return "{\"role\":\"" + role + "\",\"status\":\"" + status + "\"}";
    }

    private static String teamMember(String email, String role) {
        return "{\"fullName\":\"New Member\",\"email\":\"" + email + "\","
                + "\"password\":\"Str0ng-password\",\"role\":\"" + role + "\"}";
    }

    // ---- positive control ------------------------------------------------------------------------

    @Test
    void ownerCanChangeOwnTeamMembersAccess() throws Exception {
        put("/api/v1/users/{id}/access", ownerAToken, access("STAFF", "INACTIVE"), agentA.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    // ---- another tenant's users --------------------------------------------------------------------

    @Test
    void cannotChangeAnotherTenantsTeamMemberAccess() throws Exception {
        String before = userSnapshot(agentB.getId());
        State stateBefore = state();

        put("/api/v1/users/{id}/access", ownerAToken, access("STAFF", "INACTIVE"), agentB.getId())
                .andExpect(status().isNotFound());

        assertEquals(before, userSnapshot(agentB.getId()), "tenant B's agent must be unchanged");
        assertEquals(stateBefore, state());
    }

    @Test
    void cannotChangeAnotherTenantsOwnerAccess() throws Exception {
        String before = userSnapshot(ownerB.getId());

        put("/api/v1/users/{id}/access", ownerAToken, access("AGENT", "INACTIVE"), ownerB.getId())
                .andExpect(status().isNotFound());

        assertEquals(before, userSnapshot(ownerB.getId()), "tenant B's owner must not be demoted or deactivated");
    }

    @Test
    void cannotReadAnotherTenantsUserOrSeeThemInTheTeamList() throws Exception {
        get("/api/v1/users/{id}", ownerAToken, agentB.getId()).andExpect(status().isNotFound());

        get("/api/v1/users", ownerAToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[?(@.email == 'owner-b@example.com')]").isEmpty())
                .andExpect(jsonPath("$[?(@.email == 'agent-b@example.com')]").isEmpty());
    }

    // ---- the OWNER account itself ---------------------------------------------------------------------

    @Test
    void ownerAccessCannotBeChangedEvenByTheOwner() throws Exception {
        String before = userSnapshot(ownerA.getId());

        for (String[] attempt : new String[][] { { "AGENT", "INACTIVE" }, { "OWNER", "ACTIVE" }, { "STAFF", "ACTIVE" } }) {
            put("/api/v1/users/{id}/access", ownerAToken, access(attempt[0], attempt[1]), ownerA.getId())
                    .andExpect(status().is4xxClientError());
        }

        assertEquals(before, userSnapshot(ownerA.getId()), "the owner must remain an active OWNER");
    }

    // ---- privileged role / status values ------------------------------------------------------------------

    @Test
    void cannotGrantPrivilegedOrUnknownRolesToATeamMember() throws Exception {
        String before = userSnapshot(agentA.getId());

        for (String role : List.of("OWNER", "PLATFORM_ADMIN", "MANAGER", "ADMIN", "ROLE_OWNER", "")) {
            put("/api/v1/users/{id}/access", ownerAToken, access(role, "ACTIVE"), agentA.getId())
                    .andExpect(status().is4xxClientError());
        }

        assertEquals(before, userSnapshot(agentA.getId()), "a rejected role change must not alter the user");
    }

    @Test
    void cannotSetAnUnknownStatus() throws Exception {
        String before = userSnapshot(agentA.getId());

        for (String badStatus : List.of("DELETED", "SUSPENDED", "OWNER", "")) {
            put("/api/v1/users/{id}/access", ownerAToken, access("AGENT", badStatus), agentA.getId())
                    .andExpect(status().is4xxClientError());
        }

        assertEquals(before, userSnapshot(agentA.getId()));
    }

    @Test
    void cannotCreateTeamMembersWithPrivilegedOrUnknownRoles() throws Exception {
        State before = state();

        for (String role : List.of("OWNER", "PLATFORM_ADMIN", "MANAGER", "ADMIN", "")) {
            post("/api/v1/users/team", ownerAToken, teamMember("new-" + role.toLowerCase() + "@example.com", role))
                    .andExpect(status().is4xxClientError());
        }

        assertEquals(before, state(), "no account may be created by a rejected request");
        assertTrue(userRepository.findAll().stream().noneMatch(u -> u.getEmail().startsWith("new-")));
    }

    @Test
    void validTeamMembersAreCreatedInTheCallersTenantOnly() throws Exception {
        post("/api/v1/users/team", ownerAToken, teamMember("fresh-agent@example.com", "agent"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("AGENT"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.tenantId").value(tenantA.getId().toString()));

        User created = userRepository.findByEmail("fresh-agent@example.com").orElseThrow();
        assertEquals(tenantA.getId(), created.getTenantId());
        assertEquals(2, userRepository.findAllByTenantId(tenantB.getId()).size(), "tenant B must gain no users");
    }
}
