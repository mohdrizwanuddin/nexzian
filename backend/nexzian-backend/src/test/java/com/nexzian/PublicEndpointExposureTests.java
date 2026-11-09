package com.nexzian;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * The unauthenticated website API: only published properties of ACTIVE agencies, only the
 * intended fields, nothing writable, and no way to reach another agency's data by mixing
 * slugs, property ids and image ids.
 */
class PublicEndpointExposureTests extends AuthorizationTestSupport {

    private static final String SLUG_A = "alpha-realty";
    private static final String SLUG_B = "beta-homes";

    /** A plain GET request builder (the inherited get(...) helper would shadow the static import). */
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder pub(
            String template, Object... vars) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(template, vars);
    }

    private UUID imageA;           // published property of A
    private UUID imageB;           // published property of B
    private UUID draftImageB;      // image of B's DRAFT property

    @BeforeEach
    void uploadFixtureImages() throws Exception {
        imageA = uploadImage(ownerAToken, propertyA.getId(), "alpha-photo.png");
        imageB = uploadImage(ownerBToken, propertyB.getId(), "beta-private-photo.png");
        draftImageB = uploadImage(ownerBToken, draftPropertyB.getId(), "beta-private-photo-draft.png");
    }

    // ---- positive control + field whitelist ---------------------------------------------------------

    @Test
    void publishedPropertiesAndTheirImagesAreVisibleWithOnlyPublicFields() throws Exception {
        mockMvc.perform(pub("/api/v1/public/{slug}/properties", SLUG_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("Alpha Flat"))
                .andExpect(jsonPath("$[0].id").value(propertyA.getId().toString()))
                // internal / private fields must never be serialized
                .andExpect(jsonPath("$[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$[0].status").doesNotExist())
                .andExpect(jsonPath("$[0].createdAt").doesNotExist())
                .andExpect(jsonPath("$[0].updatedAt").doesNotExist());

        mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}", SLUG_A, propertyA.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist());

        mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}/images", SLUG_A, propertyA.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(imageA.toString()))
                .andExpect(jsonPath("$[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$[0].filePath").doesNotExist())
                .andExpect(jsonPath("$[0].storedFileName").doesNotExist());
    }

    @Test
    void publicImageFileIsServedAsAnImageWithNoSniffing() throws Exception {
        mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}/images/{i}/file", SLUG_A, propertyA.getId(), imageA))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("image/png")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    // ---- unpublished properties -----------------------------------------------------------------------

    @Test
    void draftPropertyAndItsImagesAreNotPublic() throws Exception {
        mockMvc.perform(pub("/api/v1/public/{slug}/properties", SLUG_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(content().string(not(containsString("Beta Draft"))));

        mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}", SLUG_B, draftPropertyB.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}/images", SLUG_B, draftPropertyB.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}/images/{i}/file",
                SLUG_B, draftPropertyB.getId(), draftImageB))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyTheExactPublishedStatusIsPublic() throws Exception {
        // Fail closed: any other value (including a different case) hides the property everywhere.
        for (String status : List.of("DRAFT", "AVAILABLE", "SOLD", "ARCHIVED", "published", "Published", "PUBLISHED ", "")) {
            propertyB.setStatus(status);
            propertyRepository.saveAndFlush(propertyB);

            mockMvc.perform(pub("/api/v1/public/{slug}/properties", SLUG_B))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
            mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}", SLUG_B, propertyB.getId()))
                    .andExpect(status().isNotFound());
            mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}/images", SLUG_B, propertyB.getId()))
                    .andExpect(status().isNotFound());
            mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}/images/{i}/file", SLUG_B, propertyB.getId(), imageB))
                    .andExpect(status().isNotFound());
        }
    }

    // ---- suspended agency --------------------------------------------------------------------------------

    @Test
    void suspendedAgencyDisappearsFromEveryPublicRouteAndOthersAreUnaffected() throws Exception {
        setTenantStatus(tenantB, "SUSPENDED");

        mockMvc.perform(pub("/api/v1/tenants/public/{slug}", SLUG_B)).andExpect(status().isNotFound());
        mockMvc.perform(pub("/api/v1/public/{slug}/properties", SLUG_B)).andExpect(status().isNotFound());
        mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}", SLUG_B, propertyB.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}/images", SLUG_B, propertyB.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}/images/{i}/file", SLUG_B, propertyB.getId(), imageB))
                .andExpect(status().isNotFound());

        mockMvc.perform(pub("/api/v1/public/{slug}/properties", SLUG_A)).andExpect(status().isOk());
    }

    // ---- crossing agencies ----------------------------------------------------------------------------------

    @Test
    void anotherAgenciesPropertyAndImagesAreNotReachableThroughThisAgenciesSlug() throws Exception {
        // slug A + property of B
        mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}", SLUG_A, propertyB.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}/images", SLUG_A, propertyB.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}/images/{i}/file", SLUG_A, propertyB.getId(), imageB))
                .andExpect(status().isNotFound());

        // slug A + A's published property + B's image id
        mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}/images/{i}/file", SLUG_A, propertyA.getId(), imageB))
                .andExpect(status().isNotFound());

        // slug B + A's property (and A's image)
        mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}", SLUG_B, propertyA.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}/images/{i}/file", SLUG_B, propertyA.getId(), imageA))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownSlugAndUnknownIdsAreNotFound() throws Exception {
        mockMvc.perform(pub("/api/v1/public/{slug}/properties", "no-such-agency")).andExpect(status().isNotFound());
        mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}", SLUG_A, UUID.randomUUID()))
                .andExpect(status().isNotFound());
        mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}/images/{i}/file",
                SLUG_A, propertyA.getId(), UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    // ---- nothing is writable and nothing private is reachable without login --------------------------------------

    @Test
    void publicRoutesAreReadOnlyForAnonymousUsers() throws Exception {
        String listing = "/api/v1/public/" + SLUG_A + "/properties";
        String profile = "/api/v1/tenants/public/" + SLUG_A;
        long propertiesBefore = propertyRepository.count();
        long imagesBefore = imageRepository.count();

        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)) {
            call(method, listing, null, "{}").andExpect(status().isUnauthorized());
            call(method, listing + "/" + propertyA.getId(), null, "{}").andExpect(status().isUnauthorized());
            call(method, listing + "/" + propertyA.getId() + "/images", null, "{}").andExpect(status().isUnauthorized());
            call(method, profile, null, "{}").andExpect(status().isUnauthorized());
        }

        assertEquals(propertiesBefore, propertyRepository.count());
        assertEquals(imagesBefore, imageRepository.count());
    }

    @Test
    void publicProfileHidesPrivateAgencyFields() throws Exception {
        mockMvc.perform(pub("/api/v1/tenants/public/{slug}", SLUG_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessName").value("Alpha Realty"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.subscriptionPlan").doesNotExist());
    }
}
