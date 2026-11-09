package com.nexzian;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.nexzian.entity.Lead;
import com.nexzian.entity.Property;

/**
 * R3: property archive (soft delete) lifecycle.
 *
 * DELETE /properties/{id} used to try a physical delete, which failed with a foreign-key 500
 * once a property had images and, when it worked, destroyed agency history. It now ARCHIVES: the
 * property is hidden and frozen, but its image rows, image files and leads are kept, and the
 * owner can restore it. These tests protect four things:
 *  1. NOTHING IS DESTROYED by archiving (rows, files, leads) - checked against snapshots, because
 *     row counts alone cannot show a changed row;
 *  2. NOTHING LEAKS: an archived property disappears from active lists, dashboards and the public
 *     site (including its image URLs) while the files stay on disk;
 *  3. ARCHIVED MEANS FROZEN: no edit, no publish, no image change, no new lead link - but leads
 *     that already point at it stay readable/editable and keep showing its title;
 *  4. TENANT ISOLATION AND ROLES still hold for the two new operations (archive list, restore).
 *
 * Fixtures: agency A (slug alpha-realty) has a PUBLISHED propertyA with a lead (leadA); agency B
 * has a PUBLISHED propertyB and a DRAFT draftPropertyB (all from AuthorizationTestSupport).
 */
class PropertyArchiveLifecycleTests extends AuthorizationTestSupport {

    private static final String SLUG_A = "alpha-realty";

    private UUID imageA1;
    private UUID imageA2;
    private UUID imageA3;

    @BeforeEach
    void uploadImagesForPropertyA() throws Exception {
        // Three real uploads (rows + files) so "multiple images" is exercised with real bytes.
        imageA1 = uploadImage(ownerAToken, propertyA.getId(), "one.png");
        imageA2 = uploadImage(ownerAToken, propertyA.getId(), "two.png");
        imageA3 = uploadImage(ownerAToken, propertyA.getId(), "three.png");
    }

    // ---- archiving: what happens to the property ------------------------------------------------

    @Test
    void archivingAPropertyWithNoImagesReturns204AndRecordsWhenAndByWhom() throws Exception {
        // Why: the simplest path (this always worked before) must keep its 204 contract while
        // now being a soft delete with an audit trail.
        Property bare = saveProperty("Bare Plot", tenantA.getId(), "DRAFT");
        LocalDateTime beforeCall = LocalDateTime.now().minusSeconds(1);

        delete("/api/v1/properties/{id}", ownerAToken, bare.getId()).andExpect(status().isNoContent());

        Property stored = propertyRepository.findById(bare.getId()).orElseThrow();
        assertNotNull(stored.getDeletedAt(), "the row must be kept and flagged archived");
        assertTrue(stored.getDeletedAt().isAfter(beforeCall));
        assertEquals(ownerA.getId(), stored.getDeletedBy(), "the archiving user must be recorded");
        assertEquals("DRAFT", stored.getStatus(), "status is a business state and must not be reused as the flag");
    }

    @Test
    void archivingAPropertyWithOneImageSucceedsAndKeepsTheImageRowAndFile() throws Exception {
        // Why: this is the exact case that returned HTTP 500 before R3 (S6 finding M3).
        Property single = saveProperty("One Photo Flat", tenantA.getId(), "AVAILABLE");
        UUID image = uploadImage(ownerAToken, single.getId(), "solo.png");
        State before = state();
        String imageBefore = imageSnapshot(image);

        delete("/api/v1/properties/{id}", ownerAToken, single.getId()).andExpect(status().isNoContent());

        assertNotNull(propertyRepository.findById(single.getId()).orElseThrow().getDeletedAt());
        assertEquals(imageBefore, imageSnapshot(image), "image row and file must be untouched");
        assertEquals(before, state(), "no row or file may be removed by archiving");
    }

    @Test
    void archivingAPropertyWithSeveralImagesKeepsEveryImageRowAndFile() throws Exception {
        // Why: images (and which one is featured) are agency assets; archiving must keep all of
        // them byte-for-byte so a restore brings the listing back exactly as it was.
        State before = state();
        String a1 = imageSnapshot(imageA1);
        String a2 = imageSnapshot(imageA2);
        String a3 = imageSnapshot(imageA3);

        delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId()).andExpect(status().isNoContent());

        assertEquals(a1, imageSnapshot(imageA1));
        assertEquals(a2, imageSnapshot(imageA2));
        assertEquals(a3, imageSnapshot(imageA3));
        assertEquals(before, state(), "image rows and files on disk must be unchanged");
        assertEquals(3, imageRepository.findAll().stream()
                .filter(i -> i.getProperty().getId().equals(propertyA.getId())).count());
    }

    @Test
    void archivingLeavesLeadsIntactIncludingTheirPropertyLink() throws Exception {
        // Why: the lead history is the reason for choosing soft delete. A lead must not be
        // deleted, modified or have its property reference cleared when the property is archived.
        String leadBefore = leadSnapshot(leadA.getId());

        delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId()).andExpect(status().isNoContent());

        assertEquals(leadBefore, leadSnapshot(leadA.getId()));
        assertEquals(propertyA.getId(), leadRepository.findById(leadA.getId()).orElseThrow().getPropertyId());
    }

    @Test
    void archivingAnAlreadyArchivedPropertyIs404AndKeepsTheOriginalAuditValues() throws Exception {
        // Why: a second archive must not overwrite who/when archived it first (audit integrity).
        delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId()).andExpect(status().isNoContent());
        Property first = propertyRepository.findById(propertyA.getId()).orElseThrow();

        delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId()).andExpect(status().isNotFound());

        Property second = propertyRepository.findById(propertyA.getId()).orElseThrow();
        assertEquals(first.getDeletedAt(), second.getDeletedAt());
        assertEquals(first.getDeletedBy(), second.getDeletedBy());
    }

    // ---- visibility: owner lists, dashboard, public site -----------------------------------------

    @Test
    void archivedPropertyLeavesTheActiveListAndAppearsOnlyInTheOwnersArchivedList() throws Exception {
        // Why: archived properties must not clutter or inflate the owner's working list, yet the
        // owner needs a tenant-scoped place to find and restore them.
        delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId()).andExpect(status().isNoContent());

        get("/api/v1/properties", ownerAToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        get("/api/v1/properties/archived", ownerAToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(propertyA.getId().toString()))
                .andExpect(jsonPath("$[0].title").value("Alpha Flat"))
                .andExpect(jsonPath("$[0].deletedAt").isNotEmpty())
                // who archived it is an audit detail, not something the UI needs
                .andExpect(content().string(not(containsString("deletedBy"))))
                .andExpect(content().string(not(containsString("tenantId"))));
    }

    @Test
    void archivedListIsTenantScopedAndNeverShowsAnotherAgenciesArchivedProperties() throws Exception {
        // Why: the new endpoint is a fresh place for a tenant-isolation bug to hide.
        delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId()).andExpect(status().isNoContent());
        delete("/api/v1/properties/{id}", ownerBToken, propertyB.getId()).andExpect(status().isNoContent());

        expectNoTenantBData(get("/api/v1/properties/archived", ownerAToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(propertyA.getId().toString())));

        get("/api/v1/properties/archived", ownerBToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(propertyB.getId().toString()));
    }

    @Test
    void ownerCanStillReadAnArchivedPropertyByIdAndSeesItIsArchived() throws Exception {
        // Why: the Archived view and direct links need to open it; hiding it from the OWNER
        // would make restore impossible to review. (Only the public site must not see it.)
        delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId()).andExpect(status().isNoContent());

        get("/api/v1/properties/{id}", ownerAToken, propertyA.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedAt").isNotEmpty());
        get("/api/v1/properties/{p}/images", ownerAToken, propertyA.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    void platformAgencyCountsExcludeArchivedProperties() throws Exception {
        // Why: "dashboard" figures (property count / published count) must describe what the
        // agency actively manages and shows; archived ones would inflate them.
        get("/api/v1/admin/agencies", platformAdminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.websiteSlug=='" + SLUG_A + "')].propertyCount").value(1))
                .andExpect(jsonPath("$[?(@.websiteSlug=='" + SLUG_A + "')].publishedPropertyCount").value(1));

        delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId()).andExpect(status().isNoContent());

        get("/api/v1/admin/agencies", platformAdminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.websiteSlug=='" + SLUG_A + "')].propertyCount").value(0))
                .andExpect(jsonPath("$[?(@.websiteSlug=='" + SLUG_A + "')].publishedPropertyCount").value(0));
    }

    @Test
    void archivedPropertyDisappearsFromThePublicListAndDetail() throws Exception {
        // Why: a "deleted" listing still being public is the worst leak - customers could keep
        // seeing (and enquiring about) a property the agency removed.
        mockMvc.perform(pub("/api/v1/public/{slug}/properties", SLUG_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId()).andExpect(status().isNoContent());

        mockMvc.perform(pub("/api/v1/public/{slug}/properties", SLUG_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}", SLUG_A, propertyA.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void archivedPropertysImageUrlsAreUnreachablePubliclyWhileTheFilesStayOnDisk() throws Exception {
        // Why: image URLs are guessable-by-id links that could outlive the listing. Archiving
        // must close them (404) WITHOUT deleting the files, otherwise restore would lose photos.
        State before = state();

        delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId()).andExpect(status().isNoContent());

        mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}/images", SLUG_A, propertyA.getId()))
                .andExpect(status().isNotFound());
        for (UUID image : List.of(imageA1, imageA2, imageA3)) {
            mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}/images/{i}/file",
                            SLUG_A, propertyA.getId(), image))
                    .andExpect(status().isNotFound());
        }
        assertEquals(before, state(), "the image files must still be stored");
    }

    @Test
    void activePublicResponsesDoNotGainAnArchiveField() throws Exception {
        // Why: the public JSON shape must not change; an internal archive timestamp has no
        // business in a customer-facing payload.
        mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}", SLUG_A, propertyA.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("deletedAt"))))
                .andExpect(content().string(not(containsString("deletedBy"))));
    }

    // ---- archived means frozen ----------------------------------------------------------------------------

    @Test
    void archivedPropertyCannotBeEdited() throws Exception {
        // Why: edits to a hidden listing would never be reviewed by anyone.
        delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId()).andExpect(status().isNoContent());
        String before = propertySnapshot(propertyA.getId());

        put("/api/v1/properties/{id}", ownerAToken, propertyJson("Renamed while archived", "PUBLISHED"),
                propertyA.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("archived")));

        assertEquals(before, propertySnapshot(propertyA.getId()), "an archived property must not change");
    }

    @Test
    void archivedPropertyCannotBePublished() throws Exception {
        // Why: publishing is just a status change; it must be impossible while archived, or an
        // archived property could be made public without ever being visible to the owner.
        Property draft = saveProperty("Hidden Draft", tenantA.getId(), "DRAFT");
        delete("/api/v1/properties/{id}", ownerAToken, draft.getId()).andExpect(status().isNoContent());

        put("/api/v1/properties/{id}", ownerAToken, propertyJson("Hidden Draft", "PUBLISHED"), draft.getId())
                .andExpect(status().isConflict());

        assertEquals("DRAFT", propertyRepository.findById(draft.getId()).orElseThrow().getStatus());
        mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}", SLUG_A, draft.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void imagesOfAnArchivedPropertyCannotBeAddedFeaturedOrRemoved() throws Exception {
        // Why: archiving promises the images are preserved exactly; allowing image changes would
        // let an archived listing be altered (and a delete would remove files for good).
        delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId()).andExpect(status().isNoContent());
        State before = state();
        String a1 = imageSnapshot(imageA1);
        String a2 = imageSnapshot(imageA2);

        upload(ownerAToken, propertyA.getId(), "late.png").andExpect(status().isConflict());
        put("/api/v1/properties/{p}/images/{i}/featured", ownerAToken, null, propertyA.getId(), imageA2)
                .andExpect(status().isConflict());
        delete("/api/v1/properties/{p}/images/{i}", ownerAToken, propertyA.getId(), imageA1)
                .andExpect(status().isConflict());

        assertEquals(before, state());
        assertEquals(a1, imageSnapshot(imageA1));
        assertEquals(a2, imageSnapshot(imageA2));
    }

    // ---- leads ----------------------------------------------------------------------------------------------------

    @Test
    void newLeadCannotBeLinkedToAnArchivedProperty() throws Exception {
        // Why: new enquiries should not be attached to a listing the agency has taken down.
        delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId()).andExpect(status().isNoContent());
        State before = state();

        post("/api/v1/leads", ownerAToken, leadJson("New Customer", propertyA.getId()))
                .andExpect(status().isConflict());

        assertEquals(before, state(), "a refused lead must not be created");
    }

    @Test
    void existingLeadCannotBeRepointedAtAnArchivedProperty() throws Exception {
        // Why: "newly associated" also covers moving an existing lead to an archived property.
        Property other = saveProperty("Other Flat", tenantA.getId(), "AVAILABLE");
        Lead lead = saveLead("Carol Other", "9000000003", tenantA.getId(), other.getId());
        delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId()).andExpect(status().isNoContent());
        String before = leadSnapshot(lead.getId());

        put("/api/v1/leads/{id}", ownerAToken, leadJson("Carol Other", propertyA.getId()), lead.getId())
                .andExpect(status().isConflict());

        assertEquals(before, leadSnapshot(lead.getId()));
    }

    @Test
    void leadOfAnArchivedPropertyKeepsShowingItsTitleAndIsFlaggedArchived() throws Exception {
        // Why: the lead's history must not look like it lost its property just because the
        // property left the active list (the UI used to resolve titles from that list).
        get("/api/v1/leads/{id}", ownerAToken, leadA.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propertyTitle").value("Alpha Flat"))
                .andExpect(jsonPath("$.propertyArchived").value(false));

        delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId()).andExpect(status().isNoContent());

        get("/api/v1/leads/{id}", ownerAToken, leadA.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propertyId").value(propertyA.getId().toString()))
                .andExpect(jsonPath("$.propertyTitle").value("Alpha Flat"))
                .andExpect(jsonPath("$.propertyArchived").value(true));
        get("/api/v1/leads", ownerAToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].propertyTitle").value("Alpha Flat"))
                .andExpect(jsonPath("$[0].propertyArchived").value(true));
    }

    @Test
    void leadOfAnArchivedPropertyCanStillBeEditedAndKeepsItsPropertyLink() throws Exception {
        // Why: the edit form re-sends the lead's propertyId. If that were rejected the agency
        // could not update a customer's follow-up/status without first losing the association.
        delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId()).andExpect(status().isNoContent());

        put("/api/v1/leads/{id}", ownerAToken, leadJson("Alice Alpha (updated)", propertyA.getId()), leadA.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("Alice Alpha (updated)"))
                .andExpect(jsonPath("$.propertyId").value(propertyA.getId().toString()))
                .andExpect(jsonPath("$.propertyTitle").value("Alpha Flat"));

        assertEquals(propertyA.getId(), leadRepository.findById(leadA.getId()).orElseThrow().getPropertyId());
    }

    @Test
    void leadsNeverRevealAnotherAgenciesPropertyTitle() throws Exception {
        // Why: the new title lookup must stay tenant-scoped. A lead pointing (by a corrupted or
        // legacy id) at another agency's property must not expose that property's title.
        Lead crossLink = saveLead("Mallory Link", "9000000004", tenantA.getId(), propertyB.getId());

        get("/api/v1/leads/{id}", ownerAToken, crossLink.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propertyTitle").doesNotExist())
                .andExpect(content().string(not(containsString("Beta Villa"))));
    }

    // ---- legacy leads: property hard-deleted BEFORE archiving existed ---------------------------------------
    // Old DELETE removed the property row but left leads pointing at its id (no foreign key). Such
    // a lead's edit form re-sends that id; it used to fail with 404 so the lead could not be
    // updated at all. These tests also keep "missing" distinct from "archived" and tenant
    // isolation intact.

    @Test
    void legacyLeadWithAHardDeletedPropertyCanBeEditedAndKeepsItsLinkUntilTheUserChangesIt() throws Exception {
        UUID gone = UUID.randomUUID();   // an id that exists in no tenant, like a hard-deleted property
        Lead legacy = saveLead("Legacy Lead", "9000000010", tenantA.getId(), gone);

        put("/api/v1/leads/{id}", ownerAToken, leadJson("Legacy Lead (updated)", gone), legacy.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("Legacy Lead (updated)"))
                .andExpect(jsonPath("$.propertyId").value(gone.toString()))
                .andExpect(jsonPath("$.propertyMissing").value(true))
                .andExpect(jsonPath("$.propertyArchived").doesNotExist())
                .andExpect(jsonPath("$.propertyTitle").doesNotExist());

        assertEquals(gone, leadRepository.findById(legacy.getId()).orElseThrow().getPropertyId(),
                "the association is preserved, not silently dropped");
    }

    @Test
    void legacyLeadCanClearItsDeletedPropertyAndLinkAnActiveOne() throws Exception {
        UUID gone = UUID.randomUUID();
        Lead legacy = saveLead("Legacy Clear", "9000000011", tenantA.getId(), gone);

        put("/api/v1/leads/{id}", ownerAToken, leadJson("Legacy Clear", null), legacy.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propertyId").doesNotExist())
                .andExpect(jsonPath("$.propertyMissing").doesNotExist());

        put("/api/v1/leads/{id}", ownerAToken, leadJson("Legacy Clear", propertyA.getId()), legacy.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propertyTitle").value("Alpha Flat"))
                .andExpect(jsonPath("$.propertyMissing").value(false));
    }

    @Test
    void theLegacyExemptionOnlyCoversTheLeadsCurrentIdNotAnyNonexistentId() throws Exception {
        // Why: the exemption must not become a way to attach leads to arbitrary ids. A DIFFERENT id
        // (and any id on create) must still exist in this tenant.
        UUID gone = UUID.randomUUID();
        Lead legacy = saveLead("Legacy Repoint", "9000000012", tenantA.getId(), gone);
        String before = leadSnapshot(legacy.getId());

        put("/api/v1/leads/{id}", ownerAToken, leadJson("Legacy Repoint", UUID.randomUUID()), legacy.getId())
                .andExpect(status().isNotFound());
        post("/api/v1/leads", ownerAToken, leadJson("Brand New", gone)).andExpect(status().isNotFound());

        assertEquals(before, leadSnapshot(legacy.getId()));
    }

    @Test
    void aLeadWhoseCurrentPropertyBelongsToAnotherAgencyIsNotTreatedAsLegacyAndRevealsNothing() throws Exception {
        // Why: tenant isolation. "Missing" is only forgiven when the property exists in NO tenant;
        // a current id that exists in another agency must be rejected, so the exemption can never
        // keep or probe a cross-tenant link, and no foreign title may appear in the response.
        Lead crossLinked = saveLead("Cross Linked", "9000000013", tenantA.getId(), propertyB.getId());
        String before = leadSnapshot(crossLinked.getId());

        put("/api/v1/leads/{id}", ownerAToken, leadJson("Cross Linked", propertyB.getId()), crossLinked.getId())
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("Beta Villa"))));

        assertEquals(before, leadSnapshot(crossLinked.getId()));
    }

    @Test
    void leadResponsesDistinguishMissingFromArchivedFromActive() throws Exception {
        // Why: the UI must not offer "restore" for a property that no longer exists, nor call an
        // archived property "removed". The three states are reported with different fields.
        UUID gone = UUID.randomUUID();
        Lead legacy = saveLead("Legacy Status", "9000000014", tenantA.getId(), gone);
        delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId()).andExpect(status().isNoContent());

        get("/api/v1/leads/{id}", ownerAToken, legacy.getId())      // property missing
                .andExpect(jsonPath("$.propertyMissing").value(true))
                .andExpect(jsonPath("$.propertyArchived").doesNotExist());
        get("/api/v1/leads/{id}", ownerAToken, leadA.getId())       // property archived
                .andExpect(jsonPath("$.propertyMissing").value(false))
                .andExpect(jsonPath("$.propertyArchived").value(true))
                .andExpect(jsonPath("$.propertyTitle").value("Alpha Flat"));
        post("/api/v1/properties/{id}/restore", ownerAToken, null, propertyA.getId()).andExpect(status().isOk());
        get("/api/v1/leads/{id}", ownerAToken, leadA.getId())       // property active again
                .andExpect(jsonPath("$.propertyMissing").value(false))
                .andExpect(jsonPath("$.propertyArchived").value(false));
    }

    // ---- restore -----------------------------------------------------------------------------------------------------

    @Test
    void restoringBringsThePropertyBackToTheActiveListWithImagesAndStatusUnchanged() throws Exception {
        // Why: restore must be a true undo: same status, same images, archive fields cleared.
        String contentBefore = businessContent(propertyRepository.findById(propertyA.getId()).orElseThrow());
        String a1 = imageSnapshot(imageA1);
        State countsBefore = state();
        delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId()).andExpect(status().isNoContent());

        post("/api/v1/properties/{id}/restore", ownerAToken, null, propertyA.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(propertyA.getId().toString()))
                .andExpect(jsonPath("$.deletedAt").doesNotExist());

        get("/api/v1/properties", ownerAToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(propertyA.getId().toString()));
        get("/api/v1/properties/archived", ownerAToken).andExpect(jsonPath("$", hasSize(0)));

        Property restored = propertyRepository.findById(propertyA.getId()).orElseThrow();
        assertNull(restored.getDeletedAt());
        assertNull(restored.getDeletedBy());
        assertEquals("PUBLISHED", restored.getStatus());
        assertEquals(a1, imageSnapshot(imageA1));
        assertEquals(countsBefore, state());
        // Every business field is exactly as before; only the timestamps (touched by archive and
        // restore) are allowed to differ, which is why they are not part of the comparison.
        assertEquals(contentBefore, businessContent(restored));
    }

    @Test
    void restoredDraftIsNotPublishedAndAPublishedOneFollowsTheNormalPublicationRule() throws Exception {
        // Why: restore must never make a listing public by itself. A DRAFT stays a draft; a
        // property that was PUBLISHED when archived is public again only because its own status
        // still says PUBLISHED - the existing rule - not because restore published anything.
        Property draft = saveProperty("Restore Draft", tenantA.getId(), "DRAFT");
        delete("/api/v1/properties/{id}", ownerAToken, draft.getId()).andExpect(status().isNoContent());
        delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId()).andExpect(status().isNoContent());

        post("/api/v1/properties/{id}/restore", ownerAToken, null, draft.getId()).andExpect(status().isOk());
        post("/api/v1/properties/{id}/restore", ownerAToken, null, propertyA.getId()).andExpect(status().isOk());

        assertEquals("DRAFT", propertyRepository.findById(draft.getId()).orElseThrow().getStatus());
        mockMvc.perform(pub("/api/v1/public/{slug}/properties/{p}", SLUG_A, draft.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(pub("/api/v1/public/{slug}/properties", SLUG_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(propertyA.getId().toString()));
    }

    @Test
    void restoredPropertyCanBeEditedAgain() throws Exception {
        // Why: restore must lift the freeze, otherwise archive would be a one-way door.
        delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId()).andExpect(status().isNoContent());
        post("/api/v1/properties/{id}/restore", ownerAToken, null, propertyA.getId()).andExpect(status().isOk());

        put("/api/v1/properties/{id}", ownerAToken, propertyJson("Alpha Flat v2", "PUBLISHED"), propertyA.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Alpha Flat v2"));
    }

    @Test
    void restoringAnActivePropertyIsAHarmlessNoOp() throws Exception {
        // Why: a double-click or retry must not error or alter anything.
        String before = propertySnapshot(propertyA.getId());

        post("/api/v1/properties/{id}/restore", ownerAToken, null, propertyA.getId())
                .andExpect(status().isOk());

        assertEquals(before, propertySnapshot(propertyA.getId()));
    }

    // ---- tenant isolation and roles for the new operations ---------------------------------------------------

    @Test
    void ownerCannotArchiveOrRestoreAnotherAgenciesProperty() throws Exception {
        // Why: the archive/restore lookups are tenant-scoped; a foreign id must look nonexistent
        // (404) and change nothing - including not leaking that the id exists.
        String bBefore = propertySnapshot(propertyB.getId());
        String draftBBefore = propertySnapshot(draftPropertyB.getId());

        delete("/api/v1/properties/{id}", ownerAToken, propertyB.getId()).andExpect(status().isNotFound());
        post("/api/v1/properties/{id}/restore", ownerAToken, null, propertyB.getId()).andExpect(status().isNotFound());
        post("/api/v1/properties/{id}/restore", ownerAToken, null, UUID.randomUUID()).andExpect(status().isNotFound());

        // Also after B really archived its own property: A still cannot restore it.
        delete("/api/v1/properties/{id}", ownerBToken, propertyB.getId()).andExpect(status().isNoContent());
        String bArchived = propertySnapshot(propertyB.getId());
        post("/api/v1/properties/{id}/restore", ownerAToken, null, propertyB.getId()).andExpect(status().isNotFound());

        assertEquals(bArchived, propertySnapshot(propertyB.getId()), "A's restore attempt must not touch B");
        assertEquals(draftBBefore, propertySnapshot(draftPropertyB.getId()));
        assertNotEquals(bBefore, bArchived, "sanity: B's own archive did change B");
    }

    @Test
    void onlyAnOwnerCanArchiveRestoreOrListArchivedProperties() throws Exception {
        // Why: archiving hides listings and restoring re-activates them - both are agency
        // business decisions. Every other role (and anonymous callers) must be refused and
        // nothing may change; the archive fields in the snapshot make a wrongly allowed
        // archive visible even though no row is added or removed.
        delete("/api/v1/properties/{id}", ownerAToken, propertyA.getId()).andExpect(status().isNoContent());
        String archivedSnapshot = propertySnapshot(propertyA.getId());
        State before = state();

        for (String token : new String[] { agentAToken, staffAToken, platformAdminToken }) {
            expectForbidden(delete("/api/v1/properties/{id}", token, propertyA.getId()));
            expectForbidden(post("/api/v1/properties/{id}/restore", token, null, propertyA.getId()));
            expectForbidden(get("/api/v1/properties/archived", token));
        }
        delete("/api/v1/properties/{id}", null, propertyA.getId()).andExpect(status().isUnauthorized());
        post("/api/v1/properties/{id}/restore", null, null, propertyA.getId()).andExpect(status().isUnauthorized());
        get("/api/v1/properties/archived", null).andExpect(status().isUnauthorized());

        assertEquals(archivedSnapshot, propertySnapshot(propertyA.getId()), "still archived, untouched");
        assertEquals(before, state());
    }

    @Test
    void nonOwnersCannotArchiveAnActiveProperty() throws Exception {
        // Why: same rule as above for the ACTIVE -> archived direction, where the only
        // evidence of a wrongly allowed call is the archive fields (counts do not move).
        String before = propertySnapshot(propertyA.getId());

        for (String token : new String[] { agentAToken, staffAToken, platformAdminToken }) {
            expectForbidden(delete("/api/v1/properties/{id}", token, propertyA.getId()));
        }

        assertEquals(before, propertySnapshot(propertyA.getId()));
        assertNull(propertyRepository.findById(propertyA.getId()).orElseThrow().getDeletedAt());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    // Anonymous GET. A local name is used because a static import of MockMvcRequestBuilders.get
    // would be shadowed by the inherited get(path, token, vars) helper from AuthorizationTestSupport.
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder pub(
            String template, Object... vars) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(template, vars);
    }

    private void expectForbidden(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden());
    }

    /**
     * The property's business fields, without timestamps or archive fields. Built from named
     * getters (not by position in a snapshot string), so it cannot silently go wrong if the
     * snapshot layout changes.
     */
    private String businessContent(Property p) {
        return String.join("|", String.valueOf(p.getTenantId()), p.getTitle(), p.getDescription(),
                p.getTransactionType(), p.getPropertyCategory(), p.getPropertyType(), p.getCity(),
                String.valueOf(p.getLocality()), String.valueOf(p.getPrice()), String.valueOf(p.getAreaSqft()),
                String.valueOf(p.getBedrooms()), String.valueOf(p.getBathrooms()), p.getStatus());
    }

}
