package com.nexzian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.nexzian.entity.Lead;
import com.nexzian.entity.Property;
import com.nexzian.entity.PropertyImage;
import com.nexzian.entity.User;

/**
 * Route-by-route role matrix over every CURRENT private route. Every request carries a
 * valid CSRF token and references real ids, so a 401/403 can only come from the
 * authorization rules (not from CSRF, validation or a missing record). After each denied
 * sweep the whole database and upload directory must be unchanged.
 *
 * Roles that exist today: OWNER, AGENT, STAFF, PLATFORM_ADMIN (there is no MANAGER).
 */
class RoleAuthorizationMatrixTests extends AuthorizationTestSupport {

    private interface Call {
        org.springframework.test.web.servlet.ResultActions with(String token) throws Exception;
    }

    private record Route(String name, Call call) { }

    /** A's first image: featured. The routes below delete it and download its file. */
    private UUID imageA;
    /** A's second image: NOT featured. The set-featured route targets it (see ownerBusinessRoutes). */
    private UUID imageA2;
    /** B's image on B's published property. No route here targets it: it is a cross-tenant canary. */
    private UUID imageB;
    /** B's image on B's DRAFT property. Also a cross-tenant canary. */
    private UUID draftImageB;

    @BeforeEach
    void uploadFixtureImages() throws Exception {
        imageA = uploadImage(ownerAToken, propertyA.getId(), "alpha-photo.png");
        imageA2 = uploadImage(ownerAToken, propertyA.getId(), "alpha-photo-2.png");
        imageB = uploadImage(ownerBToken, propertyB.getId(), "beta-private-photo.png");
        draftImageB = uploadImage(ownerBToken, draftPropertyB.getId(), "beta-private-photo-draft.png");

        // Precondition for the set-featured route to be observable: it must CHANGE something.
        // (It clears every image of the property and sets the chosen one, so targeting the image
        // that is already featured would leave no trace at all.)
        assertTrue(imageRepository.findById(imageA).orElseThrow().getFeatured(), "imageA must be featured");
        assertFalse(imageRepository.findById(imageA2).orElseThrow().getFeatured(), "imageA2 must not be featured");
    }

    // ---- the routes ------------------------------------------------------------------------------------

    /** Everything under /properties, /leads and /users: OWNER-only business routes. */
    private List<Route> ownerBusinessRoutes() {
        UUID p = propertyA.getId();
        UUID l = leadA.getId();
        return List.of(
                new Route("GET /properties", t -> get("/api/v1/properties", t)),
                new Route("POST /properties", t -> post("/api/v1/properties", t, propertyJson("New", "DRAFT"))),
                new Route("GET /properties/{id}", t -> get("/api/v1/properties/{id}", t, p)),
                new Route("PUT /properties/{id}", t -> put("/api/v1/properties/{id}", t, propertyJson("Edit", "DRAFT"), p)),
                // DELETE ARCHIVES (R3). Row counts do not change when archiving, so a wrongly allowed
                // archive/restore is caught by the archive fields in the property snapshot.
                new Route("DELETE /properties/{id}", t -> delete("/api/v1/properties/{id}", t, p)),
                new Route("GET /properties/archived", t -> get("/api/v1/properties/archived", t)),
                new Route("POST /properties/{id}/restore", t -> post("/api/v1/properties/{id}/restore", t, null, p)),
                new Route("GET /properties/{id}/images", t -> get("/api/v1/properties/{id}/images", t, p)),
                new Route("POST /properties/{id}/images", t -> upload(t, p, "x.png")),
                new Route("GET /properties/{id}/images/{img}/file",
                        t -> get("/api/v1/properties/{id}/images/{i}/file", t, p, imageA)),
                // Targets the NON-featured image so that, if it were wrongly allowed, the
                // featured flag would visibly move from imageA to imageA2.
                new Route("PUT /properties/{id}/images/{img}/featured",
                        t -> put("/api/v1/properties/{id}/images/{i}/featured", t, null, p, imageA2)),
                new Route("DELETE /properties/{id}/images/{img}",
                        t -> delete("/api/v1/properties/{id}/images/{i}", t, p, imageA)),
                new Route("GET /leads", t -> get("/api/v1/leads", t)),
                new Route("POST /leads", t -> post("/api/v1/leads", t, leadJson("New Lead", p))),
                new Route("GET /leads/{id}", t -> get("/api/v1/leads/{id}", t, l)),
                new Route("PUT /leads/{id}", t -> put("/api/v1/leads/{id}", t, leadJson("Edit Lead", p), l)),
                new Route("DELETE /leads/{id}", t -> delete("/api/v1/leads/{id}", t, l)),
                new Route("GET /users", t -> get("/api/v1/users", t)),
                new Route("GET /users/{id}", t -> get("/api/v1/users/{id}", t, agentA.getId())),
                new Route("POST /users/team", t -> post("/api/v1/users/team", t,
                        "{\"fullName\":\"N\",\"email\":\"n@example.com\",\"password\":\"Str0ng-password\",\"role\":\"AGENT\"}")),
                new Route("PUT /users/{id}/access", t -> put("/api/v1/users/{id}/access", t,
                        "{\"role\":\"STAFF\",\"status\":\"ACTIVE\"}", agentA.getId())));
    }

    /** Platform-wide routes: PLATFORM_ADMIN only. */
    private List<Route> platformAdminRoutes() {
        return List.of(
                new Route("GET /admin/agencies", t -> get("/api/v1/admin/agencies", t)),
                new Route("GET /tenants", t -> get("/api/v1/tenants", t)));
    }

    /** OWNER or PLATFORM_ADMIN only. */
    private List<Route> tenantRecordRoutes() {
        return List.of(new Route("GET /tenants/{id}", t -> get("/api/v1/tenants/{id}", t, tenantA.getId())));
    }

    // ---- sweeps ---------------------------------------------------------------------------------------------

    private void assertEveryRouteReturns(int expectedStatus, String token, List<Route> routes, String who)
            throws Exception {

        State before = state();
        Map<String, String> recordsBefore = protectedRecords();
        List<String> wrong = new ArrayList<>();

        for (Route route : routes) {
            int actual = route.call().with(token).andReturn().getResponse().getStatus();
            if (actual != expectedStatus) {
                wrong.add(route.name() + " -> " + actual);
            }
        }

        assertTrue(wrong.isEmpty(), who + " must get " + expectedStatus + " on every listed route, but: " + wrong);
        assertEquals(before, state(), "a denied sweep must not add or remove any row or file");
        assertEquals(recordsBefore, protectedRecords(),
                "a denied sweep must not modify the content of any existing record");
    }

    // ---- content snapshots (row counts alone cannot see an UPDATE) -----------------------------------

    private interface Snapshot {
        String take() throws Exception;
    }

    private String orMissing(Snapshot snapshot) throws Exception {
        try {
            return snapshot.take();
        } catch (NoSuchElementException gone) {
            return "MISSING (deleted)";
        }
    }

    /**
     * Full field-by-field values (including updatedAt) of EVERY fixture record, keyed by a
     * readable label. For an image that means its database fields plus whether its file exists
     * and the file's SIZE in bytes. The file's content is NOT hashed, so a change to a file that
     * keeps the same size would not be detected. A deleted record shows as MISSING.
     *
     * What each route can write, i.e. why each group is here:
     *  - PUT/DELETE /properties/{id}            -> propertyA
     *  - POST /properties/{id}/images           -> adds a row + file (caught by state() counts)
     *  - PUT .../images/{img}/featured          -> EVERY image of propertyA (it clears all, then
     *                                              sets one): imageA AND imageA2
     *  - DELETE .../images/{img}                -> imageA (row and file)
     *  - PUT/DELETE /leads/{id}                 -> leadA
     *  - PUT /users/{id}/access                 -> agentA
     *  - POST /properties, /leads, /users/team  -> add rows (state() counts)
     *  - GET routes and /admin, /tenants        -> read-only
     *
     * Records no route can reach but which are included anyway, as cross-tenant canaries (a
     * wrongly authorized request that also escaped tenant scoping would hit them): propertyB,
     * draftPropertyB, leadB, ownerB, agentB, imageB, draftImageB, plus ownerA, staffA and the
     * platform admin (no route targets those ids).
     *
     * Deliberately NOT snapshotted: tenant rows. The only tenant routes in the matrix are GETs,
     * and no endpoint exists that updates a tenant (TenantController has no write mapping; agency
     * registration is the only writer and is not a matrix route), so there is no way for a
     * wrongly authorized request to modify one. Tenant additions or removals are still caught by
     * the tenant count in state().
     */
    private Map<String, String> protectedRecords() throws Exception {
        Map<String, String> records = new LinkedHashMap<>();
        records.put("propertyA", orMissing(() -> propertySnapshot(propertyA.getId())));
        records.put("propertyB", orMissing(() -> propertySnapshot(propertyB.getId())));
        records.put("draftPropertyB", orMissing(() -> propertySnapshot(draftPropertyB.getId())));
        records.put("leadA", orMissing(() -> leadSnapshot(leadA.getId())));
        records.put("leadB", orMissing(() -> leadSnapshot(leadB.getId())));
        records.put("ownerA", orMissing(() -> userSnapshot(ownerA.getId())));
        records.put("agentA", orMissing(() -> userSnapshot(agentA.getId())));
        records.put("staffA", orMissing(() -> userSnapshot(staffA.getId())));
        records.put("ownerB", orMissing(() -> userSnapshot(ownerB.getId())));
        records.put("agentB", orMissing(() -> userSnapshot(agentB.getId())));
        records.put("platformAdmin", orMissing(() -> userSnapshot(platformAdmin.getId())));
        records.put("imageA", orMissing(() -> imageSnapshot(imageA)));
        records.put("imageA2", orMissing(() -> imageSnapshot(imageA2)));
        records.put("imageB", orMissing(() -> imageSnapshot(imageB)));
        records.put("draftImageB", orMissing(() -> imageSnapshot(draftImageB)));
        return records;
    }

    /** Labels whose snapshot differs between the two maps. */
    private Set<String> changedLabels(Map<String, String> before, Map<String, String> after) {
        Set<String> changed = new TreeSet<>();
        for (String label : before.keySet()) {
            if (!Objects.equals(before.get(label), after.get(label))) {
                changed.add(label);
            }
        }
        return changed;
    }

    /**
     * Control for the sweeps' integrity assertion: edits that keep EVERY row count identical are
     * invisible to state() but must be visible to protectedRecords(), otherwise the assertion
     * would be vacuous for PUT-style requests.
     */
    @Test
    void integritySnapshotsDetectContentChangesThatRowCountsCannotSee() throws Exception {
        State countsBefore = state();
        Map<String, String> recordsBefore = protectedRecords();

        Property property = propertyRepository.findById(propertyA.getId()).orElseThrow();
        property.setTitle("Silently changed");
        propertyRepository.saveAndFlush(property);

        Lead lead = leadRepository.findById(leadA.getId()).orElseThrow();
        lead.setCustomerName("Silently changed");
        leadRepository.saveAndFlush(lead);

        User agent = userRepository.findById(agentA.getId()).orElseThrow();
        agent.setRole("STAFF");
        userRepository.saveAndFlush(agent);

        PropertyImage image = imageRepository.findById(imageA).orElseThrow();
        image.setFeatured(!image.getFeatured());
        imageRepository.saveAndFlush(image);

        assertEquals(countsBefore, state(), "these edits must not change any row or file count");

        assertEquals(Set.of("propertyA", "leadA", "agentA", "imageA"),
                changedLabels(recordsBefore, protectedRecords()),
                "the property, lead, agent and image edits must each be detected, and nothing else");
    }

    /**
     * Control for the additional images: every image the fixture has is covered, including the
     * non-featured second image of tenant A and BOTH of tenant B's images. A change that lives
     * only in the FILE (database row untouched) is detected when it changes the file's size or
     * removes it; a same-size content change would not be (only existence and size are compared).
     */
    @Test
    void imageSnapshotsCoverEveryImageAndFileSizeWithoutAnyRowCountChange() throws Exception {
        State countsBefore = state();
        Map<String, String> recordsBefore = protectedRecords();

        // 1. database-only change on A's second image
        PropertyImage second = imageRepository.findById(imageA2).orElseThrow();
        second.setFeatured(!second.getFeatured());
        imageRepository.saveAndFlush(second);

        // 2. FILE-only change on B's image: same row, same name; 4 bytes are appended, so the
        //    file's SIZE changes (that size change is what the snapshot detects)
        PropertyImage beta = imageRepository.findById(imageB).orElseThrow();
        Path betaFile = uploadPath().resolve(beta.getStoredFileName());
        Files.write(betaFile, new byte[] { 9, 9, 9, 9 }, StandardOpenOption.APPEND);

        // 3. database-only change on B's DRAFT-property image
        PropertyImage draftBeta = imageRepository.findById(draftImageB).orElseThrow();
        draftBeta.setOriginalFileName("tampered.png");
        imageRepository.saveAndFlush(draftBeta);

        assertEquals(countsBefore, state(),
                "none of these edits may change a row count or the number of files");
        assertEquals(Set.of("imageA2", "imageB", "draftImageB"),
                changedLabels(recordsBefore, protectedRecords()),
                "each of the three image edits must be detected, and no other record");
    }

    /**
     * Control that mimics a wrongly authorized request exactly: the real set-featured route,
     * called by the OWNER (who is allowed), moves the featured flag from imageA to imageA2.
     * It adds no row and no file, so only the snapshots can reveal it. This is why the matrix
     * targets the non-featured image; had it targeted imageA the call would change nothing.
     */
    @Test
    void aSetFeaturedRequestIsVisibleToTheSnapshotsOnlyBecauseTheMatrixTargetsTheNonFeaturedImage()
            throws Exception {

        State countsBefore = state();
        Map<String, String> recordsBefore = protectedRecords();

        // The ONLY execution of the set-featured request in this test.
        int status = put("/api/v1/properties/{id}/images/{i}/featured", ownerAToken, null,
                propertyA.getId(), imageA2).andReturn().getResponse().getStatus();
        assertEquals(200, status, "the allowed OWNER request must succeed");

        assertEquals(countsBefore, state(), "set-featured adds and removes no row or file");
        assertEquals(Set.of("imageA", "imageA2"), changedLabels(recordsBefore, protectedRecords()),
                "the flag must move from imageA to imageA2, which only the snapshots can see");
    }

    private List<Route> allPrivateRoutes() {
        List<Route> all = new ArrayList<>(ownerBusinessRoutes());
        all.addAll(platformAdminRoutes());
        all.addAll(tenantRecordRoutes());
        return all;
    }

    // ---- anonymous ---------------------------------------------------------------------------------------------

    @Test
    void anonymousUsersGet401OnEveryPrivateRoute() throws Exception {
        assertEveryRouteReturns(401, null, allPrivateRoutes(), "an anonymous caller");
    }

    @Test
    void anInvalidTokenGet401OnEveryPrivateRoute() throws Exception {
        assertEveryRouteReturns(401, "not.a.valid.jwt", allPrivateRoutes(), "a caller with a garbage token");
    }

    // ---- AGENT and STAFF -------------------------------------------------------------------------------------------

    @Test
    void agentsGet403OnEveryPrivateRoute() throws Exception {
        assertEveryRouteReturns(403, agentAToken, allPrivateRoutes(), "an AGENT");
    }

    @Test
    void staffGet403OnEveryPrivateRoute() throws Exception {
        assertEveryRouteReturns(403, staffAToken, allPrivateRoutes(), "a STAFF member");
    }

    // ---- OWNER vs platform administration -------------------------------------------------------------------------------

    @Test
    void ownersGet403OnPlatformAdminRoutes() throws Exception {
        assertEveryRouteReturns(403, ownerAToken, platformAdminRoutes(), "an OWNER");
    }

    // ---- PLATFORM_ADMIN vs tenant business routes --------------------------------------------------------------------------

    @Test
    void platformAdminGets403OnEveryTenantOwnerBusinessRoute() throws Exception {
        assertEveryRouteReturns(403, platformAdminToken, ownerBusinessRoutes(), "a PLATFORM_ADMIN");
    }

    // ---- positive controls: the matrix is not "everything is denied" ------------------------------------------------------------

    @Test
    void ownerIsNeverDeniedByAuthorizationOnItsOwnBusinessRoutes() throws Exception {
        List<String> denied = new ArrayList<>();
        for (Route route : ownerBusinessRoutes()) {
            int status = route.call().with(ownerAToken).andReturn().getResponse().getStatus();
            // 404/409 are fine here (an earlier route in the loop may have deleted a lead or
            // archived the property, which later routes then refuse); 401/403 would mean the
            // OWNER is wrongly blocked, which is all this control checks.
            if (status == 401 || status == 403) {
                denied.add(route.name() + " -> " + status);
            }
        }
        assertTrue(denied.isEmpty(), "OWNER must reach its own routes, but: " + denied);
    }

    @Test
    void platformAdminReachesPlatformRoutesAndOwnerAndAdminCanReadTheirTenantRecord() throws Exception {
        List<String> denied = new ArrayList<>();
        for (Route route : platformAdminRoutes()) {
            int status = route.call().with(platformAdminToken).andReturn().getResponse().getStatus();
            if (status != 200) {
                denied.add("admin " + route.name() + " -> " + status);
            }
        }
        for (String token : List.of(ownerAToken, platformAdminToken)) {
            int status = tenantRecordRoutes().get(0).call().with(token).andReturn().getResponse().getStatus();
            if (status != 200) {
                denied.add("GET /tenants/{id} -> " + status);
            }
        }
        assertTrue(denied.isEmpty(), "expected 200 but: " + denied);
    }

    @Test
    void otherHttpVerbsOnAnAdminRouteAreAlsoRefused() throws Exception {
        // No HTTP verb is left open on a platform-admin path for non-admins.
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)) {
            assertEquals(403, call(method, "/api/v1/admin/agencies", ownerAToken, "{}")
                    .andReturn().getResponse().getStatus(), method + " on /admin must be forbidden to OWNER");
            assertEquals(401, call(method, "/api/v1/admin/agencies", null, "{}")
                    .andReturn().getResponse().getStatus(), method + " on /admin must be 401 for anonymous");
        }
    }
}
