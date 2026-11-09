package com.nexzian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.nexzian.config.SecurityConfig;
import com.nexzian.entity.Tenant;
import com.nexzian.entity.User;
import com.nexzian.repository.TenantRepository;
import com.nexzian.repository.UserRepository;
import com.nexzian.security.JwtService;
import com.nexzian.service.PropertyService;

/**
 * Authorization behaviour that only exists in a REAL servlet container (S6 / R2).
 *
 * <h3>Why MockMvc is not enough</h3>
 * MockMvc never performs the container's "error dispatch". In a real server, when a controller
 * (or Spring MVC itself) sends an error (404 from a ResponseStatusException, 400 for bad JSON,
 * 415 for a wrong media type, an unhandled exception's 500), the container re-dispatches the
 * request to /error. Spring Security authorizes that second dispatch too, and the JWT filter does
 * not run for it, so the caller looks anonymous. Before R2 that turned every such error into a
 * misleading 401 "Authentication is required" (which the frontend reports as "session expired"),
 * and a plain deny-all fallback would not have fixed it. SecurityConfig therefore permits the
 * ERROR dispatch explicitly, and these tests prove it with real HTTP.
 *
 * They also pin the other behaviours R2 deliberately sets or preserves: deny-by-default for
 * logged-in callers on unknown paths, HEAD denied, /error not directly reachable, CORS preflight
 * working without an "OPTIONS /**" permit rule, and CSRF / JWT / 401 handling unchanged.
 *
 * Runs on its own in-memory database (a separate Spring context from the MockMvc tests) and only
 * creates, and afterwards deletes, one tenant and one owner.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:nexzian-server-error-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
class ServerErrorDispatchAndFallbackTests {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173"; // app.cors.allowed-origins in the test config

    @LocalServerPort private int port;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;

    /** Replaced by a mock so a test can make the service throw an unexpected exception on demand. */
    @MockitoBean private PropertyService propertyService;

    private final HttpClient http = HttpClient.newHttpClient();

    private Tenant tenant;
    private User owner;
    private String ownerToken;

    @BeforeEach
    void createOwner() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        tenant = new Tenant();
        tenant.setBusinessName("Server Test " + unique);
        tenant.setWebsiteSlug("server-test-" + unique);
        tenant.setEmail("server-test-" + unique + "@tenant.example");
        tenant = tenantRepository.save(tenant);

        owner = new User();
        owner.setTenantId(tenant.getId());
        owner.setFullName("Owner");
        owner.setEmail("owner-" + unique + "@server-test.example");
        owner.setPassword("not-used-these-tests-use-a-token");
        owner.setRole("OWNER");
        owner.setStatus("ACTIVE");
        owner = userRepository.save(owner);

        ownerToken = jwtService.generateToken(owner);
    }

    @AfterEach
    void removeOwner() {
        // Only this test's own rows; never deleteAll().
        userRepository.deleteById(owner.getId());
        tenantRepository.deleteById(tenant.getId());
    }

    // ---- errors raised inside the application keep their real status ---------------------------------

    /**
     * Protects against the pre-R2 bug where these errors surfaced as 401. Each request is
     * authorized normally (the OWNER may call the route), then the application sends an error via
     * sendError, which triggers the error dispatch:
     *  - 404: LeadService throws ResponseStatusException for a property that does not exist;
     *  - 400: a malformed JSON body (HttpMessageNotReadableException, no custom handler);
     *  - 415: a JSON body sent to the multipart image-upload route.
     * The body must be Spring Boot's error page for that exact status, proving the ERROR dispatch
     * reached it instead of being rejected as anonymous.
     */
    @Test
    void errorsSentByTheApplicationKeepTheirRealStatusInsteadOfBecoming401() throws Exception {
        String csrf = csrf();

        Response unknownProperty = send("POST", "/api/v1/leads", ownerToken, csrf,
                "{\"customerName\":\"X\",\"phone\":\"9111111111\",\"propertyId\":\"" + UUID.randomUUID() + "\"}");
        assertEquals(404, unknownProperty.status, "ResponseStatusException(404) must not become 401");
        assertTrue(unknownProperty.body.contains("\"status\":404"), unknownProperty.body);
        // LeadService's own reason text ("Property not found for this agency") must not be echoed.
        assertErrorBodyRevealsNoInternals(unknownProperty, "Property not found");

        Response malformedJson = send("POST", "/api/v1/leads", ownerToken, csrf, "{ this is not json");
        assertEquals(400, malformedJson.status, "an unreadable body must be 400, not 401");
        assertTrue(malformedJson.body.contains("\"status\":400"), malformedJson.body);
        // Jackson's parser diagnostics (token, line and column) must not be echoed.
        assertErrorBodyRevealsNoInternals(malformedJson, "JSON parse error", "Unrecognized token");

        Response wrongMediaType = send("POST", "/api/v1/properties/" + UUID.randomUUID() + "/images",
                ownerToken, csrf, "{}");
        assertEquals(415, wrongMediaType.status, "a JSON body on the multipart route must be 415, not 401");
        assertTrue(wrongMediaType.body.contains("\"status\":415"), wrongMediaType.body);
        // Spring's explanation of which media types the route accepts must not be echoed.
        assertErrorBodyRevealsNoInternals(wrongMediaType, "is not supported", "multipart");
    }

    /**
     * An exception nobody handles (GlobalExceptionHandler has no catch-all) reaches the container,
     * which answers 500 and error-dispatches. That must stay a 500, not a 401. A mock service
     * throws on demand so the test does not rely on any existing bug to produce the failure.
     */
    @Test
    void anUnhandledExceptionIsReportedAs500NotAsUnauthorized() throws Exception {
        when(propertyService.getAllProperties(any())).thenThrow(new IllegalStateException("simulated failure"));

        Response response = send("GET", "/api/v1/properties", ownerToken, null, null);

        assertEquals(500, response.status);
        assertTrue(response.body.contains("\"status\":500"), response.body);
        // The most important case: the exception's own message and class are exactly what an
        // unhandled failure must never reveal to the client.
        assertErrorBodyRevealsNoInternals(response, "simulated failure", "IllegalStateException");
    }

    // ---- deny-by-default as a real client sees it ---------------------------------------------------------

    /**
     * Protects the final deny-all rule end to end. A logged-in OWNER asking for a path that does not
     * exist is refused (403), not told "404", and an anonymous caller gets 401, so the API does not
     * reveal which paths exist to anyone who may not call them.
     */
    @Test
    void unknownPathsAreDeniedForEveryCallerInsteadOfFallingThroughToNotFound() throws Exception {
        assertEquals(403, send("GET", "/api/v1/definitely-not-a-route", ownerToken, null, null).status);
        assertEquals(401, send("GET", "/api/v1/definitely-not-a-route", null, null, null).status);
    }

    /**
     * /error exists only for the container's error dispatch. Requested directly it must be refused
     * (before R2 a logged-in user got Boot's meaningless "status 999" page).
     */
    @Test
    void theErrorEndpointCannotBeRequestedDirectly() throws Exception {
        assertEquals(401, send("GET", "/error", null, null, null).status);
        assertEquals(403, send("GET", "/error", ownerToken, null, null).status);
    }

    /**
     * HEAD is deliberately not supported (the approved R2 decision): method-specific rules do not
     * cover it, so even the OWNER is refused on a route they may GET.
     */
    @Test
    void headRequestsAreDenied() throws Exception {
        assertEquals(200, send("GET", "/api/v1/leads", ownerToken, null, null).status, "control: GET is allowed");
        assertEquals(403, send("HEAD", "/api/v1/leads", ownerToken, null, null).status);
    }

    // ---- preserved behaviour: CORS, CSRF, JWT, 401 ---------------------------------------------------------

    /**
     * The blanket "OPTIONS /**" permit was removed in R2. A browser preflight must still succeed,
     * because Spring's CORS filter answers it before authorization; a plain OPTIONS (no CORS
     * headers) is an ordinary request and is now treated as one: anonymous gets 401.
     */
    @Test
    void corsPreflightStillWorksWithoutABlanketOptionsRule() throws Exception {
        HttpRequest preflight = HttpRequest.newBuilder(uri("/api/v1/leads"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", ALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,x-xsrf-token")
                .build();
        HttpResponse<String> response = http.send(preflight, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(ALLOWED_ORIGIN, response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertEquals("true", response.headers().firstValue("Access-Control-Allow-Credentials").orElse(null));

        assertEquals(401, send("OPTIONS", "/api/v1/leads", null, null, null).status,
                "a non-CORS OPTIONS is no longer a free pass");
    }

    /** Anonymous callers still get Spring Security's 401 on protected routes (entry point unchanged). */
    @Test
    void anonymousCallersStillGetTheSecurity401() throws Exception {
        Response response = send("GET", "/api/v1/leads", null, null, null);

        assertEquals(401, response.status);
        assertTrue(response.body.contains(SecurityConfig.UNAUTHORIZED_MESSAGE), response.body);
    }

    /** CSRF is still enforced: a state-changing request without the token is refused with 403. */
    @Test
    void csrfProtectionIsStillEnforced() throws Exception {
        Response response = send("POST", "/api/v1/leads", ownerToken, null, "{}");

        assertEquals(403, response.status);
    }

    /** The JWT filter still rejects a garbage token with its own 401 before any rule is consulted. */
    @Test
    void anInvalidJwtIsStillRejectedWith401() throws Exception {
        assertEquals(401, send("GET", "/api/v1/leads", "not.a.valid.jwt", null, null).status);
    }

    /**
     * Responses a controller produces itself (no sendError, so no error dispatch) are unaffected:
     * an unknown public agency is a plain 404 for anonymous callers, and a lead id that does not
     * exist is a plain 404 for the OWNER.
     */
    @Test
    void notFoundsReturnedDirectlyByControllersAreUnchanged() throws Exception {
        assertEquals(404, send("GET", "/api/v1/public/no-such-agency/properties", null, null, null).status);
        assertEquals(404, send("GET", "/api/v1/tenants/public/no-such-agency", null, null, null).status);
        assertEquals(404, send("GET", "/api/v1/leads/" + UUID.randomUUID(), ownerToken, null, null).status);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private record Response(int status, String body) { }

    /**
     * Asserts that an error response, as a real client receives it, discloses nothing about the
     * server's internals.
     *
     * Why this matters: anyone who can make the server fail (a malformed request is enough) sees
     * the error body. A stack trace, an exception class name or an exception message tells an
     * attacker which framework and versions are running, package and class names, file paths,
     * SQL fragments and constraint names, or the data a query was working on. That is free
     * reconnaissance for finding the next vulnerability, and it can leak other customers'
     * data directly. Clients only need the status code; details belong in the server log.
     *
     * Spring Boot hides all of this by default: in this Boot version (4.1.x) the settings are
     * spring.web.error.include-message / -stacktrace / -exception / -binding-errors, all "never"
     * by default, and the project sets none of them. (The older server.error.include-* names no
     * longer influence this error page - tried and verified - so setting them would give a false
     * sense of control.) This check locks the safe behaviour in: it was verified to FAIL, with the
     * exception class, stack trace and our own source line numbers visible in the body, when those
     * spring.web.error.* settings are switched on, so a future change to them (or a custom error
     * controller) cannot start leaking details unnoticed.
     *
     * First it requires the keys of the normal minimal error page, so the "must not contain" checks
     * below cannot pass vacuously on an empty or unrelated body.
     *
     * @param mustNotAppear extra text specific to the scenario (the exception's own message, the
     *                      parser's diagnostics...) that must not be echoed
     */
    private static void assertErrorBodyRevealsNoInternals(Response response, String... mustNotAppear) {
        String body = response.body;

        for (String expectedKey : List.of("timestamp", "status", "error", "path")) {
            assertTrue(body.contains("\"" + expectedKey + "\":"),
                    "expected Spring Boot's minimal error JSON but got: " + body);
        }

        for (String forbiddenKey : List.of("message", "trace", "exception", "errors")) {
            assertFalse(body.contains("\"" + forbiddenKey + "\":"),
                    "the error body must not contain a '" + forbiddenKey + "' field: " + body);
        }

        // Textual fingerprints of a stack trace or an exception, wherever they might be placed.
        for (String fingerprint : List.of("\tat ", "at com.nexzian", "org.springframework", "java.lang.", "Exception")) {
            assertFalse(body.contains(fingerprint),
                    "the error body must not contain '" + fingerprint + "': " + body);
        }

        for (String secret : mustNotAppear) {
            assertFalse(body.contains(secret),
                    "the error body must not echo '" + secret + "': " + body);
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /** Fetches a CSRF token the way the frontend does: from the cookie set by /auth/csrf. */
    private String csrf() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return response.headers().allValues("Set-Cookie").stream()
                .filter(cookie -> cookie.startsWith("XSRF-TOKEN="))
                .map(cookie -> cookie.substring("XSRF-TOKEN=".length(), cookie.indexOf(';')))
                .findFirst().orElseThrow();
    }

    /** Sends a real HTTP request. A null csrf means no CSRF header or cookie; a null body means no body. */
    private Response send(String method, String path, String token, String csrf, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (csrf != null) {
            builder.header("Cookie", "XSRF-TOKEN=" + csrf).header("X-XSRF-TOKEN", csrf);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));

        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body() == null ? "" : response.body());
    }
}
