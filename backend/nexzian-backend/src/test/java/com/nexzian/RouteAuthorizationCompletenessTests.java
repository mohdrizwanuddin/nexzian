package com.nexzian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import com.nexzian.config.SecurityConfig;

import jakarta.servlet.http.Cookie;

/**
 * Proves the URL authorization policy is DENY-BY-DEFAULT and complete (S6 / R2).
 *
 * <h3>Why this class exists</h3>
 * Authorization lives in SecurityConfig as URL rules. Before R2 the last rule was "any
 * authenticated user", so a new controller endpoint nobody wrote a rule for was silently open
 * to every role. These tests make that mistake impossible to miss: a new endpoint must be added
 * to the policy table below AND to SecurityConfig, otherwise a test fails.
 *
 * <h3>How it works (and why it is not just a copied route list)</h3>
 * <ol>
 *   <li><b>Discovery:</b> the routes are read from the live Spring MVC handler mappings
 *       (controllers, plus the actuator endpoints), never from a hand-written list.</li>
 *   <li><b>Two-way reconciliation:</b> the discovered routes must equal the test-owned
 *       {@link #POLICY} table exactly. A route missing from the table is a NEW route nobody
 *       decided about; a table entry with no route is STALE. Discovered routes are only
 *       compared by name, NEVER executed: an unknown route could have side effects.</li>
 *   <li><b>Behaviour:</b> only routes declared in the table are executed, with vetted
 *       requests that cannot change data even if authorization were wrong (random ids that
 *       cannot exist, empty bodies that fail validation, a JSON body sent to a multipart
 *       endpoint). Each is called as five principals and must be allowed or denied exactly as
 *       declared. After the run the whole database and upload directory must be unchanged.</li>
 * </ol>
 *
 * Spring Security's own 401/403 are recognised by their exact body ({@link SecurityConfig}
 * constants), so a 401/403 produced by a controller (e.g. refresh without a cookie, a
 * suspended agency) is never mistaken for "authorization denied".
 */
class RouteAuthorizationCompletenessTests extends AuthorizationTestSupport {

    /** Who may call a route. Mirrors the groups in SecurityConfig. */
    private enum Access { PUBLIC, AUTHENTICATED, OWNER, OWNER_OR_PLATFORM_ADMIN, PLATFORM_ADMIN }

    /** The five callers every declared route is tried with. */
    private enum Principal { ANONYMOUS, OWNER, AGENT, STAFF, PLATFORM_ADMIN }

    /** PASSED = authorization let the request through (whatever the controller then answered). */
    private enum Outcome { PASSED, DENIED_401, DENIED_403 }

    private record Policy(String method, String pattern, Access access) {
        /** Method + pattern with variable NAMES erased, so renaming {id} to {userId} is not a change. */
        String key() {
            return method + " " + normalise(pattern);
        }
    }

    /**
     * Routes that exist in Spring's mappings but are verified elsewhere, so they are excluded from
     * the two-way comparison: /error is the container's error page (accepts any method; reachable
     * only through error dispatch, covered by ServerErrorDispatchAndFallbackTests).
     */
    private static final Set<String> FRAMEWORK_ROUTES_VERIFIED_ELSEWHERE = Set.of("/error");

    /**
     * URL-pattern handlers Spring registers for static content. The backend serves no static
     * files, so these must stay denied; if someone adds another URL mapping this test fails.
     */
    private static final Set<String> EXPECTED_URL_HANDLER_PATTERNS = Set.of("/**", "/webjars/**");

    /** An id that cannot exist in the database, so id-based requests only ever "not find" something. */
    private static final String UNUSED_ID = UUID.randomUUID().toString();

    // =====================================================================================
    // THE INTENDED POLICY (test-owned). Every entry is checked against the real routes AND
    // against real behaviour. Keep it in the same groups as SecurityConfig.
    // =====================================================================================
    private static final List<Policy> POLICY = List.of(
            // ---- PUBLIC: sign-in, sign-up and the public agency website -------------------------
            // Must be reachable without logging in or the product cannot be used at all.
            route("POST", "/api/v1/auth/login", Access.PUBLIC),
            route("POST", "/api/v1/auth/logout", Access.PUBLIC),
            route("POST", "/api/v1/auth/refresh", Access.PUBLIC),
            route("POST", "/api/v1/auth/register-agency", Access.PUBLIC),
            route("GET", "/api/v1/auth/csrf", Access.PUBLIC),
            route("GET", "/api/v1/tenants/public/{websiteSlug}", Access.PUBLIC),
            route("GET", "/api/v1/public/{websiteSlug}/properties", Access.PUBLIC),
            route("GET", "/api/v1/public/{websiteSlug}/properties/{propertyId}", Access.PUBLIC),
            route("GET", "/api/v1/public/{websiteSlug}/properties/{propertyId}/images", Access.PUBLIC),
            route("GET", "/api/v1/public/{websiteSlug}/properties/{propertyId}/images/{imageId}/file", Access.PUBLIC),
            route("GET", "/actuator/health", Access.PUBLIC),

            // ---- AUTHENTICATED (any role): preserved from the old fallback rule ---------------------
            route("GET", "/api/v1", Access.AUTHENTICATED),
            route("GET", "/actuator", Access.AUTHENTICATED),
            route("GET", "/actuator/health/**", Access.AUTHENTICATED),

            // ---- PLATFORM_ADMIN: platform-wide data no agency user may see --------------------------
            route("GET", "/api/v1/admin/agencies", Access.PLATFORM_ADMIN),
            route("GET", "/api/v1/tenants", Access.PLATFORM_ADMIN),

            // ---- OWNER or PLATFORM_ADMIN: one tenant record (the controller adds an ownership check) --
            route("GET", "/api/v1/tenants/{id}", Access.OWNER_OR_PLATFORM_ADMIN),

            // ---- OWNER only: the agency's business data (AGENT/STAFF get no business permissions yet) --
            route("GET", "/api/v1/properties", Access.OWNER),
            route("POST", "/api/v1/properties", Access.OWNER),
            route("GET", "/api/v1/properties/{id}", Access.OWNER),
            route("PUT", "/api/v1/properties/{id}", Access.OWNER),
            route("DELETE", "/api/v1/properties/{id}", Access.OWNER),
            // R3 archive lifecycle: DELETE above archives; these two list / restore archived ones.
            route("GET", "/api/v1/properties/archived", Access.OWNER),
            route("POST", "/api/v1/properties/{id}/restore", Access.OWNER),
            route("GET", "/api/v1/properties/{propertyId}/images", Access.OWNER),
            route("POST", "/api/v1/properties/{propertyId}/images", Access.OWNER),
            route("GET", "/api/v1/properties/{propertyId}/images/{imageId}/file", Access.OWNER),
            route("PUT", "/api/v1/properties/{propertyId}/images/{imageId}/featured", Access.OWNER),
            route("DELETE", "/api/v1/properties/{propertyId}/images/{imageId}", Access.OWNER),
            route("GET", "/api/v1/leads", Access.OWNER),
            route("POST", "/api/v1/leads", Access.OWNER),
            route("GET", "/api/v1/leads/{id}", Access.OWNER),
            route("PUT", "/api/v1/leads/{id}", Access.OWNER),
            route("DELETE", "/api/v1/leads/{id}", Access.OWNER),
            route("GET", "/api/v1/users", Access.OWNER),
            route("GET", "/api/v1/users/{userId}", Access.OWNER),
            route("POST", "/api/v1/users/team", Access.OWNER),
            route("PUT", "/api/v1/users/{userId}/access", Access.OWNER));

    private static Policy route(String method, String pattern, Access access) {
        return new Policy(method, pattern, access);
    }

    @Autowired private ApplicationContext applicationContext;

    // =====================================================================================
    // 1. Discovery vs policy, in both directions (nothing is executed here)
    // =====================================================================================

    /**
     * Protects against a new endpoint shipping without an authorization decision, and against the
     * policy table silently drifting from reality.
     *  - route registered but not in POLICY  => "undeclared": nobody decided who may call it;
     *  - POLICY entry with no registered route => "stale": the table no longer describes the app.
     */
    @Test
    void registeredRoutesAndThePolicyTableMatchExactlyInBothDirections() {
        Set<String> discovered = discoveredRouteKeys();
        Set<String> declared = new TreeSet<>();
        POLICY.forEach(policy -> declared.add(policy.key()));

        Set<String> undeclared = new TreeSet<>(discovered);
        undeclared.removeAll(declared);
        Set<String> stale = new TreeSet<>(declared);
        stale.removeAll(discovered);

        assertTrue(undeclared.isEmpty(),
                "NEW route(s) without a policy decision: " + undeclared
                        + " -> add an explicit rule to SecurityConfig AND an entry to POLICY");
        assertTrue(stale.isEmpty(),
                "STALE policy entr(ies) for route(s) that are no longer registered: " + stale);
        assertTrue(discovered.size() > 30,
                "route discovery looks broken: only " + discovered.size() + " routes found");
    }

    /** The policy table itself must not contain the same route twice (a duplicate hides a mistake). */
    @Test
    void thePolicyTableHasNoDuplicateRoutes() {
        Set<String> keys = new TreeSet<>();
        List<String> duplicates = new ArrayList<>();
        for (Policy policy : POLICY) {
            if (!keys.add(policy.key())) {
                duplicates.add(policy.key());
            }
        }
        assertTrue(duplicates.isEmpty(), "duplicate policy entries: " + duplicates);
    }

    /**
     * Static-content handlers (/** and /webjars/**) exist but serve nothing. They are not part of
     * the table; this guards against someone registering another URL-pattern handler (a new static
     * folder, a downloads path...) that would then need a deliberate authorization decision.
     */
    @Test
    void noUnexpectedUrlPatternHandlersAreRegistered() {
        Set<String> patterns = new TreeSet<>();
        for (AbstractUrlHandlerMapping mapping : applicationContext.getBeansOfType(AbstractUrlHandlerMapping.class).values()) {
            patterns.addAll(mapping.getHandlerMap().keySet());
        }
        assertEquals(EXPECTED_URL_HANDLER_PATTERNS, patterns,
                "a new URL-pattern (static resource) handler was registered; decide who may reach it");
    }

    // =====================================================================================
    // 2. Declared routes behave exactly as declared (only vetted, side-effect-free requests)
    // =====================================================================================

    /**
     * Protects against three different mistakes in SecurityConfig, each reported by name:
     *  - INCORRECTLY PERMITTED: a role that must be refused got through (privilege escalation);
     *  - INCORRECTLY DENIED: a role that must be allowed was refused (broken feature);
     *  - UNCOVERED: a declared route is refused to EVERY caller, which with a deny-all fallback
     *    means SecurityConfig has no rule for it (the exact situation a forgotten rule creates).
     * Refusals must also be the right kind: 401 for anonymous callers, 403 for logged-in ones.
     * Finally nothing in the database or upload directory may change, proving the probes are inert.
     */
    @Test
    void everyDeclaredRouteIsAllowedAndDeniedExactlyAsDeclared() throws Exception {
        Cookie csrf = csrfCookie();
        State stateBefore = state();
        Map<String, String> recordsBefore = fixtureRecords();
        List<String> problems = new ArrayList<>();

        for (Policy policy : POLICY) {
            String path = concretise(policy.pattern());
            boolean passedForAnyone = false;
            List<String> routeProblems = new ArrayList<>();

            for (Principal who : Principal.values()) {
                Outcome actual = probe(policy.method(), path, who, csrf);
                Outcome expected = expectedOutcome(policy.access(), who);
                passedForAnyone |= actual == Outcome.PASSED;

                if (actual != expected) {
                    String kind = expected == Outcome.PASSED ? "INCORRECTLY DENIED"
                            : actual == Outcome.PASSED ? "INCORRECTLY PERMITTED" : "WRONG DENIAL KIND";
                    routeProblems.add(kind + " " + who + " (expected " + expected + ", got " + actual + ")");
                }
            }

            if (!passedForAnyone) {
                problems.add("UNCOVERED " + policy.method() + " " + policy.pattern()
                        + ": denied to every caller, so SecurityConfig has no rule for it");
            } else {
                routeProblems.forEach(p -> problems.add(policy.method() + " " + policy.pattern() + " -> " + p));
            }
        }

        assertTrue(problems.isEmpty(), "authorization does not match the declared policy:\n  " + String.join("\n  ", problems));
        assertEquals(stateBefore, state(), "probing must not add, remove or change any row or file");
        assertEquals(recordsBefore, fixtureRecords(), "probing must not modify any existing record");
    }

    // =====================================================================================
    // 3. The deny-all fallback: anything not declared is refused to everyone
    // =====================================================================================

    /**
     * Protects the final "deny everything else" rule. None of these requests matches a declared
     * route, so every caller (even OWNER and PLATFORM_ADMIN) must be refused by Spring Security:
     * 401 when anonymous, 403 when logged in. Before R2 a logged-in user got a 404 here, and the
     * fallback "any authenticated user" meant a forgotten rule opened a route to every role.
     * Also covered: the wrong HTTP method on a real path, HEAD, a plain OPTIONS, the legacy
     * endpoints removed in S2, actuator endpoints that are not exposed, and /error requested directly.
     */
    @Test
    void anythingNotDeclaredIsDeniedToEveryCaller() throws Exception {
        Cookie csrf = csrfCookie();
        String id = UNUSED_ID;
        List<String[]> undeclaredRequests = List.of(
                new String[] { "GET", "/api/v1/definitely-not-a-route" },
                new String[] { "GET", "/definitely-not-a-route" },
                new String[] { "GET", "/api/v1/admin/anything-else" },
                new String[] { "POST", "/api/v1/tenants" },                          // removed in S2
                new String[] { "POST", "/api/v1/users/register" },                   // removed in S2
                new String[] { "PUT", "/api/v1/tenants/" + id },                     // tenants are read-only
                new String[] { "PATCH", "/api/v1/properties" },                      // method not declared
                new String[] { "GET", "/api/v1/auth/login" },                        // login is POST only
                new String[] { "DELETE", "/api/v1/auth/csrf" },
                new String[] { "HEAD", "/api/v1/properties" },                       // HEAD is deliberately denied
                new String[] { "HEAD", "/api/v1/public/probe-slug/properties" },
                new String[] { "OPTIONS", "/api/v1/leads" },                         // plain OPTIONS, not a CORS preflight
                new String[] { "GET", "/actuator/info" },                            // not exposed
                new String[] { "GET", "/actuator/env" },                             // not exposed
                new String[] { "GET", "/favicon.ico" },
                new String[] { "GET", "/webjars/probe/file.js" },
                new String[] { "GET", "/error" });                                   // only reachable via error dispatch

        List<String> problems = new ArrayList<>();
        for (String[] undeclared : undeclaredRequests) {
            for (Principal who : Principal.values()) {
                Outcome actual = probe(undeclared[0], undeclared[1], who, csrf);
                Outcome expected = who == Principal.ANONYMOUS ? Outcome.DENIED_401 : Outcome.DENIED_403;
                if (actual != expected) {
                    problems.add(undeclared[0] + " " + undeclared[1] + " as " + who + ": expected " + expected + ", got " + actual);
                }
            }
        }
        assertTrue(problems.isEmpty(), "undeclared requests must be denied to everyone:\n  " + String.join("\n  ", problems));
    }

    /**
     * Regression for an over-broad rule that existed before R2: "GET /tenants/public/**" also matched
     * the bare path /tenants/public, which then reached the "/tenants/{id}" handler without a login.
     * With exact patterns that path is governed by the {id} rule, so an anonymous caller gets 401.
     */
    @Test
    void theBarePublicSegmentIsNotRoutedToAnotherHandlerAnonymously() throws Exception {
        assertEquals(Outcome.DENIED_401, probe("GET", "/api/v1/tenants/public", Principal.ANONYMOUS, csrfCookie()));
    }

    // =====================================================================================
    // 4. The test's own yardstick: Spring Security denials vs controller 401/403
    // =====================================================================================

    /**
     * Protects the whole suite from a false sense of security. If the classifier treated any
     * 401/403 as "authorization denied", a controller answering 401/403 (refresh without cookie,
     * suspended agency) would look like a missing permission. It must recognise ONLY Spring
     * Security's own responses.
     */
    @Test
    void theClassifierSeparatesSpringSecurityDenialsFromControllerGenerated401And403() throws Exception {
        Cookie csrf = csrfCookie();

        // Spring Security denials.
        assertEquals(Outcome.DENIED_401, probe("GET", "/api/v1/properties", Principal.ANONYMOUS, csrf));
        assertEquals(Outcome.DENIED_403, probe("GET", "/api/v1/properties", Principal.AGENT, csrf));

        // A controller-generated 401: refresh without a cookie. Authorization let it through.
        MvcResult refresh = mockMvc.perform(request(HttpMethod.POST, "/api/v1/auth/refresh")
                .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())).andReturn();
        assertEquals(401, refresh.getResponse().getStatus());
        assertEquals(Outcome.PASSED, classify(refresh), "a controller's own 401 is not an authorization denial");

        // A service-generated 403: correct password, but the agency is suspended (S4).
        setTenantStatus(tenantA, "SUSPENDED");
        MvcResult suspended = post("/api/v1/auth/login", null,
                "{\"email\":\"owner-a@example.com\",\"password\":\"" + PASSWORD + "\"}").andReturn();
        assertEquals(403, suspended.getResponse().getStatus());
        assertEquals(Outcome.PASSED, classify(suspended), "a service's own 403 is not an authorization denial");
    }

    // =====================================================================================
    // helpers
    // =====================================================================================

    /** What the policy says should happen for this caller on this kind of route. */
    private static Outcome expectedOutcome(Access access, Principal who) {
        boolean allowed = switch (access) {
            case PUBLIC -> true;
            case AUTHENTICATED -> who != Principal.ANONYMOUS;
            case OWNER -> who == Principal.OWNER;
            case OWNER_OR_PLATFORM_ADMIN -> who == Principal.OWNER || who == Principal.PLATFORM_ADMIN;
            case PLATFORM_ADMIN -> who == Principal.PLATFORM_ADMIN;
        };
        if (allowed) {
            return Outcome.PASSED;
        }
        // Spring Security answers 401 to anonymous callers and 403 to logged-in ones.
        return who == Principal.ANONYMOUS ? Outcome.DENIED_401 : Outcome.DENIED_403;
    }

    private String tokenOf(Principal who) {
        return switch (who) {
            case ANONYMOUS -> null;
            case OWNER -> ownerAToken;
            case AGENT -> agentAToken;
            case STAFF -> staffAToken;
            case PLATFORM_ADMIN -> platformAdminToken;
        };
    }

    /**
     * Runs one request through the full security filter chain as the given principal.
     * State-changing methods carry a valid CSRF token so a CSRF rejection can never be mistaken
     * for an authorization decision. POST/PUT get an empty JSON object: it fails bean validation
     * (400) or, on the multipart upload route, fails content negotiation (415), so even a request
     * that authorization wrongly lets through creates or changes nothing.
     */
    private Outcome probe(String method, String path, Principal who, Cookie csrf) throws Exception {
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(method), path);

        String token = tokenOf(who);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (!method.equals("GET") && !method.equals("HEAD")) {
            builder.cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
        }
        if (method.equals("POST") || method.equals("PUT")) {
            builder.contentType(MediaType.APPLICATION_JSON).content("{}");
        }

        try {
            return classify(mockMvc.perform(builder).andReturn());
        } catch (jakarta.servlet.ServletException handlerFailedAfterAuthorization) {
            // The request got past authorization and then failed inside the application.
            return Outcome.PASSED;
        }
    }

    /**
     * A response is an authorization denial only if it is Spring Security's own 401/403, recognised
     * by the exact body SecurityConfig writes. Any other response, including a controller's 401/403,
     * means authorization let the request through.
     */
    private static Outcome classify(MvcResult result) throws UnsupportedEncodingException {
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        if (status == 401 && body.equals(securityBody(401, "Unauthorized", SecurityConfig.UNAUTHORIZED_MESSAGE))) {
            return Outcome.DENIED_401;
        }
        if (status == 403 && body.equals(securityBody(403, "Forbidden", SecurityConfig.FORBIDDEN_MESSAGE))) {
            return Outcome.DENIED_403;
        }
        return Outcome.PASSED;
    }

    private static String securityBody(int status, String error, String message) {
        return "{\"status\":" + status + ",\"error\":\"" + error + "\",\"message\":\"" + message + "\"}";
    }

    /** Variable NAMES are erased so "{id}" vs "{userId}" never causes a false mismatch. */
    private static String normalise(String pattern) {
        return pattern.replaceAll("\\{[^/}]+}", "{}");
    }

    /** A request path that cannot exist in the database: ids become an unused UUID, "/**" becomes "/probe". */
    private static String concretise(String pattern) {
        return pattern.replace("{websiteSlug}", "probe-slug")
                .replaceAll("\\{[^/}]+}", UNUSED_ID)
                .replace("/**", "/probe");
    }

    /**
     * Every route Spring MVC has registered (controllers AND the actuator mapping, which is also a
     * RequestMappingInfoHandlerMapping), as "METHOD normalised-pattern". A mapping with no method
     * restriction is reported as ANY.
     */
    private Set<String> discoveredRouteKeys() {
        Set<String> keys = new TreeSet<>();
        for (RequestMappingInfoHandlerMapping mapping
                : applicationContext.getBeansOfType(RequestMappingInfoHandlerMapping.class).values()) {
            mapping.getHandlerMethods().forEach((info, handler) -> {
                Set<String> methods = new TreeSet<>();
                info.getMethodsCondition().getMethods().forEach(m -> methods.add(m.name()));
                if (methods.isEmpty()) {
                    methods.add("ANY");
                }
                for (String pattern : info.getPatternValues()) {
                    if (FRAMEWORK_ROUTES_VERIFIED_ELSEWHERE.contains(pattern)) {
                        continue;
                    }
                    for (String method : methods) {
                        keys.add(method + " " + normalise(pattern));
                    }
                }
            });
        }
        return keys;
    }

    /** Content snapshots of every fixture record, to prove the probes changed nothing. */
    private Map<String, String> fixtureRecords() {
        Map<String, String> records = new java.util.LinkedHashMap<>();
        records.put("propertyA", propertySnapshot(propertyA.getId()));
        records.put("propertyB", propertySnapshot(propertyB.getId()));
        records.put("draftPropertyB", propertySnapshot(draftPropertyB.getId()));
        records.put("leadA", leadSnapshot(leadA.getId()));
        records.put("leadB", leadSnapshot(leadB.getId()));
        for (var user : List.of(ownerA, agentA, staffA, ownerB, agentB, platformAdmin)) {
            records.put("user:" + user.getEmail(), userSnapshot(user.getId()));
        }
        return records;
    }
}
