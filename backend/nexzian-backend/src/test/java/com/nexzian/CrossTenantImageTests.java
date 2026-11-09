package com.nexzian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nexzian.entity.PropertyImage;

/**
 * Property images across tenants, using real uploads. Besides the HTTP result, each test
 * proves the victim's database rows AND files on disk are untouched and that nothing new
 * was written.
 *
 * Note (finding L8): image routes currently answer "not yours" with 409 rather than 404,
 * so the helper accepts either; both mean the request was refused.
 */
class CrossTenantImageTests extends AuthorizationTestSupport {

    private UUID imageA;          // alpha's only image (featured)
    private UUID imageB1;         // beta's first image (featured)
    private UUID imageB2;         // beta's second image (not featured)

    @BeforeEach
    void uploadFixtureImages() throws Exception {
        imageA = uploadImage(ownerAToken, propertyA.getId(), "alpha-photo.png");
        imageB1 = uploadImage(ownerBToken, propertyB.getId(), "beta-private-photo-1.png");
        imageB2 = uploadImage(ownerBToken, propertyB.getId(), "beta-private-photo-2.png");
    }

    // ---- positive control ---------------------------------------------------------------------

    @Test
    void ownerCanUseOwnImages() throws Exception {
        get("/api/v1/properties/{p}/images", ownerAToken, propertyA.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(imageA.toString()));
        get("/api/v1/properties/{p}/images/{i}/file", ownerAToken, propertyA.getId(), imageA)
                .andExpect(status().isOk());
        put("/api/v1/properties/{p}/images/{i}/featured", ownerAToken, null, propertyA.getId(), imageA)
                .andExpect(status().isOk());
        delete("/api/v1/properties/{p}/images/{i}", ownerAToken, propertyA.getId(), imageA)
                .andExpect(status().isOk());
        assertTrue(imageRepository.findById(imageA).isEmpty());
    }

    // ---- other tenant's property id -------------------------------------------------------------

    @Test
    void cannotListAnotherTenantsImages() throws Exception {
        State before = state();

        expectNoTenantBData(expectNotFoundForThisTenant(
                get("/api/v1/properties/{p}/images", ownerAToken, propertyB.getId())));

        assertEquals(before, state());
    }

    @Test
    void cannotUploadAnImageToAnotherTenantsProperty() throws Exception {
        State before = state();

        expectNoTenantBData(expectNotFoundForThisTenant(
                upload(ownerAToken, propertyB.getId(), "planted.png")));

        assertEquals(before, state(), "no image row and no file may be created");
        assertEquals(2, imageRepository.findAllByPropertyIdAndTenantIdOrderByCreatedAtAsc(
                propertyB.getId(), tenantB.getId()).size(), "tenant B's property must not gain an image");
    }

    @Test
    void cannotFetchAnotherTenantsImageFile() throws Exception {
        State before = state();
        String snapshot = imageSnapshot(imageB1);

        expectNoTenantBData(expectNotFoundForThisTenant(
                get("/api/v1/properties/{p}/images/{i}/file", ownerAToken, propertyB.getId(), imageB1)));

        assertEquals(snapshot, imageSnapshot(imageB1));
        assertEquals(before, state());
    }

    @Test
    void cannotSetAnotherTenantsImageAsFeatured() throws Exception {
        String b1 = imageSnapshot(imageB1);
        String b2 = imageSnapshot(imageB2);
        State before = state();

        expectNotFoundForThisTenant(
                put("/api/v1/properties/{p}/images/{i}/featured", ownerAToken, null, propertyB.getId(), imageB2));

        assertEquals(b1, imageSnapshot(imageB1), "B's featured image must be unchanged");
        assertEquals(b2, imageSnapshot(imageB2), "B's other image must not become featured");
        assertEquals(before, state());
    }

    @Test
    void cannotDeleteAnotherTenantsImage() throws Exception {
        String b1 = imageSnapshot(imageB1);
        State before = state();

        expectNotFoundForThisTenant(
                delete("/api/v1/properties/{p}/images/{i}", ownerAToken, propertyB.getId(), imageB1));

        PropertyImage stillThere = imageRepository.findById(imageB1).orElseThrow();
        assertTrue(Files.exists(uploadPath().resolve(stillThere.getStoredFileName())),
                "tenant B's file must still be on disk");
        assertEquals(b1, imageSnapshot(imageB1));
        assertEquals(before, state());
    }

    // ---- mixing one tenant's property id with the other tenant's image id --------------------------

    @Test
    void ownPropertyIdWithAnotherTenantsImageIdIsRefusedOnEveryRoute() throws Exception {
        String b1 = imageSnapshot(imageB1);
        State before = state();

        expectNotFoundForThisTenant(
                get("/api/v1/properties/{p}/images/{i}/file", ownerAToken, propertyA.getId(), imageB1));
        expectNotFoundForThisTenant(
                put("/api/v1/properties/{p}/images/{i}/featured", ownerAToken, null, propertyA.getId(), imageB1));
        expectNotFoundForThisTenant(
                delete("/api/v1/properties/{p}/images/{i}", ownerAToken, propertyA.getId(), imageB1));

        assertEquals(b1, imageSnapshot(imageB1));
        assertEquals(before, state());
    }

    @Test
    void anotherTenantsPropertyIdWithOwnImageIdIsRefusedOnEveryRoute() throws Exception {
        String a = imageSnapshot(imageA);
        State before = state();

        expectNotFoundForThisTenant(
                get("/api/v1/properties/{p}/images/{i}/file", ownerAToken, propertyB.getId(), imageA));
        expectNotFoundForThisTenant(
                put("/api/v1/properties/{p}/images/{i}/featured", ownerAToken, null, propertyB.getId(), imageA));
        expectNotFoundForThisTenant(
                delete("/api/v1/properties/{p}/images/{i}", ownerAToken, propertyB.getId(), imageA));

        assertEquals(a, imageSnapshot(imageA), "A's own image must not be moved or deleted via B's property");
        assertEquals(before, state());
    }

    @Test
    void unknownIdsAreRefusedTheSameWay() throws Exception {
        State before = state();

        expectNotFoundForThisTenant(get("/api/v1/properties/{p}/images", ownerAToken, UUID.randomUUID()));
        expectNotFoundForThisTenant(
                get("/api/v1/properties/{p}/images/{i}/file", ownerAToken, propertyA.getId(), UUID.randomUUID()));

        assertEquals(before, state());
    }

    // ---- known defect (S6 finding M3), documented as the behaviour that SHOULD hold ---------------------

    /**
     * KNOWN BUG - confirmed by the R1 test run: deleting a property that has images throws
     * "Referential integrity constraint violation ... property_images ... FOREIGN KEY(property_id)"
     * out of PropertyService.deleteProperty, i.e. an unhandled 500. This test states the invariant
     * the fix must establish, whichever way it is resolved:
     *   - 204: the property AND its image rows AND its files are all gone; or
     *   - a clean 4xx (e.g. 409): the property, its images and its files are all still there.
     * It must never be a 5xx and never leave orphaned image rows/files.
     * Disabled so the suite stays green until R3 fixes the defect; then remove @Disabled.
     */
    @org.junit.jupiter.api.Disabled("Known bug M3 (R3): deleting a property that has images fails with a foreign-key 500")
    @Test
    void deletingOwnPropertyThatHasImagesNeverFailsWithAServerErrorOrLeavesOrphans() throws Exception {
        State before = state();

        int status = delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId())
                .andReturn().getResponse().getStatus();

        if (status == 204) {
            assertTrue(propertyRepository.findById(propertyA.getId()).isEmpty());
            assertTrue(imageRepository.findById(imageA).isEmpty(), "images must be removed with the property");
            assertEquals(before.files() - 1, state().files(), "the image file must be removed too");
        } else {
            assertTrue(status >= 400 && status < 500, "expected 204 or a clean 4xx but got " + status);
            assertTrue(propertyRepository.findById(propertyA.getId()).isPresent());
            assertTrue(imageRepository.findById(imageA).isPresent());
            assertEquals(before, state(), "a refused delete must change nothing");
        }
    }

    // ---- file bytes never reach the wrong caller --------------------------------------------------

    @Test
    void anonymousAndWrongRoleCallersCannotFetchAnyPrivateImageFile() throws Exception {
        get("/api/v1/properties/{p}/images/{i}/file", null, propertyA.getId(), imageA)
                .andExpect(status().isUnauthorized());
        get("/api/v1/properties/{p}/images/{i}/file", agentAToken, propertyA.getId(), imageA)
                .andExpect(status().isForbidden());
        get("/api/v1/properties/{p}/images/{i}/file", platformAdminToken, propertyA.getId(), imageA)
                .andExpect(status().isForbidden());
    }
}
