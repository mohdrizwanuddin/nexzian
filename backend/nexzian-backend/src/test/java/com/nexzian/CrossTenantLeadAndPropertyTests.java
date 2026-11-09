package com.nexzian;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import com.nexzian.entity.Property;

/**
 * Agency A's owner against agency B's leads and properties. Every rejected request must
 * return the expected status, leave B's records byte-for-byte unchanged and create nothing.
 */
class CrossTenantLeadAndPropertyTests extends AuthorizationTestSupport {

    // ================================ leads ==================================================

    @Test
    void ownerCanReadUpdateAndDeleteOwnLead() throws Exception {
        // Positive control: proves the negative tests below are not passing because a route is broken.
        get("/api/v1/leads/{id}", ownerAToken, leadA.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("Alice Alpha"));

        put("/api/v1/leads/{id}", ownerAToken, leadJson("Alice Updated", propertyA.getId()), leadA.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("Alice Updated"));

        delete("/api/v1/leads/{id}", ownerAToken, leadA.getId()).andExpect(status().isNoContent());
        assertTrue(leadRepository.findById(leadA.getId()).isEmpty());
    }

    @Test
    void cannotReadAnotherTenantsLead() throws Exception {
        String before = leadSnapshot(leadB.getId());
        State stateBefore = state();

        expectNoTenantBData(get("/api/v1/leads/{id}", ownerAToken, leadB.getId())
                .andExpect(status().isNotFound()));

        assertEquals(before, leadSnapshot(leadB.getId()));
        assertEquals(stateBefore, state());
    }

    @Test
    void cannotUpdateAnotherTenantsLead() throws Exception {
        String before = leadSnapshot(leadB.getId());
        State stateBefore = state();

        expectNoTenantBData(put("/api/v1/leads/{id}", ownerAToken,
                leadJson("Hijacked Name", propertyA.getId()), leadB.getId())
                .andExpect(status().isNotFound()));

        assertEquals(before, leadSnapshot(leadB.getId()), "tenant B's lead must be unchanged");
        assertEquals(stateBefore, state());
    }

    @Test
    void cannotDeleteAnotherTenantsLead() throws Exception {
        String before = leadSnapshot(leadB.getId());
        State stateBefore = state();

        delete("/api/v1/leads/{id}", ownerAToken, leadB.getId()).andExpect(status().isNotFound());

        assertTrue(leadRepository.findById(leadB.getId()).isPresent(), "tenant B's lead must still exist");
        assertEquals(before, leadSnapshot(leadB.getId()));
        assertEquals(stateBefore, state());
    }

    @Test
    void cannotCreateALeadLinkedToAnotherTenantsProperty() throws Exception {
        State stateBefore = state();

        expectNoTenantBData(post("/api/v1/leads", ownerAToken,
                leadJson("Sneaky Lead", propertyB.getId()))
                .andExpect(status().isNotFound()));

        assertEquals(stateBefore, state(), "no lead may be created");
        assertEquals(0, leadRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantA.getId()).stream()
                .filter(l -> "Sneaky Lead".equals(l.getCustomerName())).count());
    }

    @Test
    void cannotRepointOwnLeadAtAnotherTenantsProperty() throws Exception {
        String before = leadSnapshot(leadA.getId());
        State stateBefore = state();

        expectNoTenantBData(put("/api/v1/leads/{id}", ownerAToken,
                leadJson("Alice Alpha", propertyB.getId()), leadA.getId())
                .andExpect(status().isNotFound()));

        assertEquals(before, leadSnapshot(leadA.getId()), "the lead must not be re-linked to tenant B's property");
        assertEquals(stateBefore, state());
    }

    @Test
    void invalidLeadStatusIsRejectedAndLeavesTheLeadUnchanged() throws Exception {
        String before = leadSnapshot(leadA.getId());

        put("/api/v1/leads/{id}", ownerAToken,
                "{\"customerName\":\"Changed Name\",\"phone\":\"9111111111\",\"status\":\"ADMIN_APPROVED\"}",
                leadA.getId())
                .andExpect(status().is4xxClientError());

        assertEquals(before, leadSnapshot(leadA.getId()), "a rejected update must not be partially saved");
    }

    @Test
    void leadListContainsOnlyTheCallersTenant() throws Exception {
        expectNoTenantBData(get("/api/v1/leads", ownerAToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].customerName").value("Alice Alpha")));
    }

    // ============================== properties ===============================================

    @Test
    void ownerCanReadOwnProperty() throws Exception {
        get("/api/v1/properties/{id}", ownerAToken, propertyA.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Alpha Flat"));
    }

    @Test
    void cannotReadAnotherTenantsProperty() throws Exception {
        String before = propertySnapshot(propertyB.getId());
        State stateBefore = state();

        expectNoTenantBData(get("/api/v1/properties/{id}", ownerAToken, propertyB.getId())
                .andExpect(status().isNotFound()));

        assertEquals(before, propertySnapshot(propertyB.getId()));
        assertEquals(stateBefore, state());
    }

    @Test
    void cannotReadAnotherTenantsUnpublishedProperty() throws Exception {
        expectNoTenantBData(get("/api/v1/properties/{id}", ownerAToken, draftPropertyB.getId())
                .andExpect(status().isNotFound()));
    }

    @Test
    void cannotDeleteAnotherTenantsProperty() throws Exception {
        String before = propertySnapshot(propertyB.getId());
        String draftBefore = propertySnapshot(draftPropertyB.getId());
        State stateBefore = state();

        delete("/api/v1/properties/{id}", ownerAToken, propertyB.getId()).andExpect(status().isNotFound());
        delete("/api/v1/properties/{id}", ownerAToken, draftPropertyB.getId()).andExpect(status().isNotFound());

        assertEquals(before, propertySnapshot(propertyB.getId()), "tenant B's property must still exist, unchanged");
        assertEquals(draftBefore, propertySnapshot(draftPropertyB.getId()));
        assertEquals(stateBefore, state());
    }

    @Test
    void cannotUpdateAnotherTenantsPropertyAndNothingIsModified() throws Exception {
        String before = propertySnapshot(propertyB.getId());
        State stateBefore = state();

        expectNoTenantBData(put("/api/v1/properties/{id}", ownerAToken,
                propertyJson("Hijacked Title", "PUBLISHED"), propertyB.getId())
                .andExpect(status().isNotFound()));

        assertEquals(before, propertySnapshot(propertyB.getId()));
        assertEquals(stateBefore, state());
    }

    @Test
    void propertyListContainsOnlyTheCallersTenant() throws Exception {
        expectNoTenantBData(get("/api/v1/properties", ownerAToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("Alpha Flat"))
                .andExpect(content().string(not(containsString("tenantId")))));
    }

    @Test
    void ownerCanArchiveOwnPropertyWithoutAffectingAnotherTenant() throws Exception {
        String bBefore = propertySnapshot(propertyB.getId());

        delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId()).andExpect(status().isNoContent());

        // DELETE archives (soft delete): the row stays, flagged archived and attributed to the owner.
        Property archived = propertyRepository.findById(propertyA.getId()).orElseThrow();
        assertNotNull(archived.getDeletedAt());
        assertEquals(ownerA.getId(), archived.getDeletedBy());
        // The other tenant's property must be byte-for-byte untouched.
        assertEquals(bBefore, propertySnapshot(propertyB.getId()));
    }
}
