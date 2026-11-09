package com.nexzian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;
import com.nexzian.entity.Lead;
import com.nexzian.entity.Property;
import com.nexzian.entity.User;

/**
 * Clients must not be able to choose tenant ownership, ids, roles, status or plan by adding
 * fields to request bodies. Extra fields are ignored (Jackson default) or the request is
 * rejected; either way tenant B is untouched and tenant ownership never changes.
 *
 * (Agency registration with role / tenantId / id / status / subscriptionPlan in the body is
 * already covered by AgencyRegistrationTests.clientCannotChooseTenantRoleStatusOrPlan.)
 */
class MassAssignmentTests extends AuthorizationTestSupport {

    private String smuggled() {
        return ",\"tenantId\":\"" + tenantB.getId() + "\",\"id\":\"" + UUID.randomUUID() + "\","
                + "\"role\":\"PLATFORM_ADMIN\",\"subscriptionPlan\":\"ENTERPRISE\","
                + "\"createdAt\":\"2000-01-01T00:00:00\",\"ownerId\":\"" + ownerB.getId() + "\"";
    }

    /** Appends extra top-level members to a JSON object string. */
    private String withExtras(String json) {
        return json.substring(0, json.length() - 1) + smuggled() + "}";
    }

    private String idOf(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    // ---- properties --------------------------------------------------------------------------------------------

    @Test
    void createdPropertyBelongsToTheCallerNoMatterWhatTheBodyClaims() throws Exception {
        String bBefore = propertySnapshot(propertyB.getId());
        long bCountBefore = propertyRepository.findAllByTenantId(tenantB.getId()).size();
        String suppliedId = UUID.randomUUID().toString();
        String body = propertyJson("Smuggler", "DRAFT");
        body = body.substring(0, body.length() - 1)
                + ",\"tenantId\":\"" + tenantB.getId() + "\",\"id\":\"" + suppliedId + "\"}";

        MvcResult created = post("/api/v1/properties", ownerAToken, body)
                .andExpect(status().isCreated()).andReturn();

        Property stored = propertyRepository.findById(UUID.fromString(idOf(created))).orElseThrow();
        assertEquals(tenantA.getId(), stored.getTenantId(), "the property must belong to the caller's tenant");
        assertNotEquals(suppliedId, stored.getId().toString(), "the server must generate the id");
        assertEquals(bCountBefore, propertyRepository.findAllByTenantId(tenantB.getId()).size());
        assertEquals(bBefore, propertySnapshot(propertyB.getId()));
    }

    @Test
    void updatingAPropertyCannotMoveItToAnotherTenantOrChangeItsId() throws Exception {
        UUID original = propertyA.getId();

        put("/api/v1/properties/{id}", ownerAToken, withExtras(propertyJson("Renamed", "DRAFT")), original)
                .andExpect(status().isOk());

        Property stored = propertyRepository.findById(original).orElseThrow();
        assertEquals("Renamed", stored.getTitle(), "legitimate fields are applied");
        assertEquals(tenantA.getId(), stored.getTenantId(), "tenant ownership must never change");
        assertEquals(1, propertyRepository.findAllByTenantId(tenantA.getId()).size());
    }

    // ---- leads -----------------------------------------------------------------------------------------------------

    @Test
    void createdLeadBelongsToTheCallerAndStartsAsNew() throws Exception {
        String bBefore = leadSnapshot(leadB.getId());
        String body = withExtras(leadJson("Smuggler Lead", propertyA.getId()));
        body = body.substring(0, body.length() - 1) + ",\"status\":\"CONVERTED\"}";

        MvcResult created = post("/api/v1/leads", ownerAToken, body).andExpect(status().isCreated()).andReturn();

        Lead stored = leadRepository.findById(UUID.fromString(idOf(created))).orElseThrow();
        assertEquals(tenantA.getId(), stored.getTenantId());
        assertEquals("NEW", stored.getStatus(), "a client cannot create a lead in an arbitrary status");
        assertEquals(bBefore, leadSnapshot(leadB.getId()));
    }

    @Test
    void updatingALeadCannotMoveItToAnotherTenant() throws Exception {
        put("/api/v1/leads/{id}", ownerAToken, withExtras(leadJson("Alice Renamed", propertyA.getId())), leadA.getId())
                .andExpect(status().isOk());

        Lead stored = leadRepository.findById(leadA.getId()).orElseThrow();
        assertEquals("Alice Renamed", stored.getCustomerName());
        assertEquals(tenantA.getId(), stored.getTenantId(), "tenant ownership must never change");
    }

    // ---- team members ------------------------------------------------------------------------------------------------

    @Test
    void createdTeamMemberStaysInTheCallersTenantWithTheRequestedSafeRoleOnly() throws Exception {
        String body = "{\"fullName\":\"Smuggler\",\"email\":\"smuggler@example.com\","
                + "\"password\":\"Str0ng-password\",\"role\":\"AGENT\","
                + "\"tenantId\":\"" + tenantB.getId() + "\",\"status\":\"INACTIVE\","
                + "\"id\":\"" + UUID.randomUUID() + "\",\"subscriptionPlan\":\"ENTERPRISE\"}";

        post("/api/v1/users/team", ownerAToken, body).andExpect(status().isCreated());

        User stored = userRepository.findByEmail("smuggler@example.com").orElseThrow();
        assertEquals(tenantA.getId(), stored.getTenantId(), "the account must be created in the caller's tenant");
        assertEquals("AGENT", stored.getRole());
        assertEquals("ACTIVE", stored.getStatus(), "status is decided by the server on creation");
        assertEquals(2, userRepository.findAllByTenantId(tenantB.getId()).size(), "tenant B must gain no users");
    }

    @Test
    void accessUpdateCannotMoveUsersChangeTheirEmailOrPasswordOrGrantPrivilege() throws Exception {
        User before = userRepository.findById(agentA.getId()).orElseThrow();
        String passwordHashBefore = before.getPassword();

        put("/api/v1/users/{id}/access", ownerAToken,
                "{\"role\":\"STAFF\",\"status\":\"INACTIVE\",\"tenantId\":\"" + tenantB.getId() + "\","
                        + "\"email\":\"taken-over@example.com\",\"password\":\"Overwritten-1\","
                        + "\"fullName\":\"Renamed\",\"id\":\"" + UUID.randomUUID() + "\"}",
                agentA.getId())
                .andExpect(status().isOk());

        User after = userRepository.findById(agentA.getId()).orElseThrow();
        assertEquals("STAFF", after.getRole(), "the two supported fields are applied");
        assertEquals("INACTIVE", after.getStatus());
        assertEquals(tenantA.getId(), after.getTenantId(), "the user must stay in the caller's tenant");
        assertEquals("agent-a@example.com", after.getEmail());
        assertEquals(passwordHashBefore, after.getPassword(), "the password must not be changeable here");
        assertEquals("agent-a@example.com", after.getFullName(), "unsupported fields are ignored");
        assertTrue(userRepository.findByEmail("taken-over@example.com").isEmpty());
    }

    @Test
    void everyMassAssignmentAttemptLeavesTenantBExactlyAsItWas() throws Exception {
        String property = propertySnapshot(propertyB.getId());
        String draft = propertySnapshot(draftPropertyB.getId());
        String lead = leadSnapshot(leadB.getId());
        String owner = userSnapshot(ownerB.getId());
        String agent = userSnapshot(agentB.getId());

        post("/api/v1/properties", ownerAToken, withExtras(propertyJson("X1", "DRAFT"))).andExpect(status().isCreated());
        post("/api/v1/leads", ownerAToken, withExtras(leadJson("X2", propertyA.getId()))).andExpect(status().isCreated());
        put("/api/v1/properties/{id}", ownerAToken, withExtras(propertyJson("X3", "DRAFT")), propertyA.getId())
                .andExpect(status().isOk());
        put("/api/v1/leads/{id}", ownerAToken, withExtras(leadJson("X4", propertyA.getId())), leadA.getId())
                .andExpect(status().isOk());

        assertEquals(property, propertySnapshot(propertyB.getId()));
        assertEquals(draft, propertySnapshot(draftPropertyB.getId()));
        assertEquals(lead, leadSnapshot(leadB.getId()));
        assertEquals(owner, userSnapshot(ownerB.getId()));
        assertEquals(agent, userSnapshot(agentB.getId()));
        assertEquals(2, propertyRepository.findAllByTenantId(tenantB.getId()).size());
        assertEquals(1, leadRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantB.getId()).size());
    }
}
