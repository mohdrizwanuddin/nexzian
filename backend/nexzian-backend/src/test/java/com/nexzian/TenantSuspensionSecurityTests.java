package com.nexzian;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.nexzian.entity.Property;
import com.nexzian.entity.RefreshToken;
import com.nexzian.entity.Tenant;
import com.nexzian.entity.User;
import com.nexzian.repository.PropertyRepository;
import com.nexzian.repository.RefreshTokenRepository;
import com.nexzian.repository.TenantRepository;
import com.nexzian.repository.UserRepository;
import com.nexzian.security.JwtService;

import jakarta.servlet.http.Cookie;

/**
 * A suspended (non-ACTIVE) agency must lose access to every private API: new logins,
 * refresh tokens and access tokens issued before the suspension.
 * Public agency website behaviour and unrelated agencies must be unaffected.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TenantSuspensionSecurityTests {

    private static final String PASSWORD = "test-password";
    private static final String OWNER_EMAIL = "owner-a@example.com";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private PropertyRepository propertyRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private Tenant tenantA;
    private Tenant tenantB;
    private User ownerA;
    private User agentA;
    private User ownerB;
    private User platformAdmin;

    @BeforeEach
    void setUp() {
        propertyRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();

        tenantA = createTenant("Alpha Realty", "alpha-realty");
        tenantB = createTenant("Beta Homes", "beta-homes");
        ownerA = createUser(OWNER_EMAIL, tenantA.getId(), "OWNER");
        agentA = createUser("agent-a@example.com", tenantA.getId(), "AGENT");
        ownerB = createUser("owner-b@example.com", tenantB.getId(), "OWNER");
        platformAdmin = createUser("admin@example.com", new UUID(0L, 0L), "PLATFORM_ADMIN");

        saveProperty("Alpha flat", tenantA.getId(), "PUBLISHED");
    }

    // 1 + 2 -----------------------------------------------------------------------

    @Test
    void suspendedOwnerGets403WithClearMessageAndNoSessionAfterCorrectPassword() throws Exception {
        setStatus(tenantA, "SUSPENDED");
        long refreshTokensBefore = refreshTokenRepository.count();

        login(OWNER_EMAIL, PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value(containsString("suspended")))
                .andExpect(header().doesNotExist("Set-Cookie"));

        assertEquals(refreshTokensBefore, refreshTokenRepository.count(),
                "no refresh token may be issued to a suspended agency");
    }

    @Test
    void wrongPasswordOnSuspendedTenantStillReturnsGeneric401() throws Exception {
        setStatus(tenantA, "SUSPENDED");

        login(OWNER_EMAIL, "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"))
                .andExpect(content().string(not(containsString("suspended"))));
    }

    // 3 ---------------------------------------------------------------------------

    @Test
    void accessTokenIssuedBeforeSuspensionStopsWorkingImmediately() throws Exception {
        String token = jwtService.generateToken(ownerA);

        for (String path : List.of("/api/v1/properties", "/api/v1/leads", "/api/v1/users")) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }

        setStatus(tenantA, "SUSPENDED");

        for (String path : List.of("/api/v1/properties", "/api/v1/leads", "/api/v1/users")) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        // The HttpOnly cookie form of the same token is refused too.
        mockMvc.perform(get("/api/v1/properties").cookie(new Cookie("nexzian_access", token)))
                .andExpect(status().isUnauthorized());
        // And so is a state-changing call: nothing is created.
        long propertiesBefore = propertyRepository.count();
        Cookie csrf = csrfCookie();
        mockMvc.perform(post("/api/v1/properties")
                .header("Authorization", "Bearer " + token)
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"X\",\"transactionType\":\"SALE\",\"propertyCategory\":\"RESIDENTIAL\","
                        + "\"propertyType\":\"APARTMENT\",\"city\":\"Hyderabad\",\"price\":1,\"areaSqft\":10,"
                        + "\"status\":\"DRAFT\"}"))
                .andExpect(status().isUnauthorized());
        assertEquals(propertiesBefore, propertyRepository.count());
    }

    // 4 + 6: refresh while suspended, and behaviour after reactivation --------------

    @Test
    void refreshIsRejectedWhileSuspendedWithoutRevokingTheTokenAndWorksAgainAfterReactivation()
            throws Exception {
        MvcResult loginResult = login(OWNER_EMAIL, PASSWORD).andExpect(status().isOk()).andReturn();
        Cookie refreshCookie = cookieFrom(loginResult, "nexzian_refresh");
        long refreshTokensBefore = refreshTokenRepository.count();

        setStatus(tenantA, "SUSPENDED");

        refresh(refreshCookie)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("suspended")))
                .andExpect(header().doesNotExist("Set-Cookie"));

        // S4 does not delete or revoke refresh-token rows.
        assertEquals(refreshTokensBefore, refreshTokenRepository.count());
        for (RefreshToken stored : tokensOf(ownerA)) {
            assertNull(stored.getRevokedAt(), "suspension must not revoke refresh tokens");
        }

        // DOCUMENTED BEHAVIOUR: because the row was neither revoked nor rotated, the SAME
        // refresh token is accepted again once the agency is reactivated.
        setStatus(tenantA, "ACTIVE");
        refresh(refreshCookie).andExpect(status().isNoContent());
    }

    // 5 ---------------------------------------------------------------------------

    @Test
    void agentOfSuspendedTenantCannotLogIn() throws Exception {
        setStatus(tenantA, "SUSPENDED");

        login("agent-a@example.com", PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    // 6 ---------------------------------------------------------------------------

    @Test
    void reactivationRestoresLoginAndApiAccess() throws Exception {
        String oldToken = jwtService.generateToken(ownerA);
        setStatus(tenantA, "SUSPENDED");
        login(OWNER_EMAIL, PASSWORD).andExpect(status().isForbidden());

        setStatus(tenantA, "ACTIVE");

        login(OWNER_EMAIL, PASSWORD).andExpect(status().isOk());
        // DOCUMENTED BEHAVIOUR: a not-yet-expired access token works again, because
        // it was never revoked, only refused while the agency was not ACTIVE.
        mockMvc.perform(get("/api/v1/properties").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isOk());
    }

    // 7 ---------------------------------------------------------------------------

    @Test
    void otherActiveAgencyIsUnaffected() throws Exception {
        setStatus(tenantA, "SUSPENDED");

        login("owner-b@example.com", PASSWORD).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/properties")
                .header("Authorization", "Bearer " + jwtService.generateToken(ownerB)))
                .andExpect(status().isOk());
    }

    // 8 ---------------------------------------------------------------------------

    @Test
    void platformAdminIsExemptEvenWhenEveryAgencyIsSuspended() throws Exception {
        setStatus(tenantA, "SUSPENDED");
        setStatus(tenantB, "SUSPENDED");

        login("admin@example.com", PASSWORD).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/agencies")
                .header("Authorization", "Bearer " + jwtService.generateToken(platformAdmin)))
                .andExpect(status().isOk());
    }

    // 9 ---------------------------------------------------------------------------

    @Test
    void everyStatusOtherThanActiveIsDeniedAndActiveIsCaseInsensitive() throws Exception {
        for (String blocked : List.of("SUSPENDED", "suspended", "INACTIVE", "Banned", "PENDING", "")) {
            setStatus(tenantA, blocked);
            login(OWNER_EMAIL, PASSWORD).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/properties")
                    .header("Authorization", "Bearer " + jwtService.generateToken(ownerA)))
                    .andExpect(status().isUnauthorized());
        }
        for (String allowed : List.of("ACTIVE", "active", "Active")) {
            setStatus(tenantA, allowed);
            login(OWNER_EMAIL, PASSWORD).andExpect(status().isOk());
        }
    }

    @Test
    void userWhoseTenantRowIsMissingIsDeniedFailClosed() throws Exception {
        createUser("orphan@example.com", UUID.randomUUID(), "OWNER");
        User orphan = userRepository.findByEmail("orphan@example.com").orElseThrow();

        login("orphan@example.com", PASSWORD).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/properties")
                .header("Authorization", "Bearer " + jwtService.generateToken(orphan)))
                .andExpect(status().isUnauthorized());
    }

    // 10 --------------------------------------------------------------------------

    @Test
    void logoutRemainsAvailableForSuspendedAgency() throws Exception {
        MvcResult loginResult = login(OWNER_EMAIL, PASSWORD).andExpect(status().isOk()).andReturn();
        Cookie refreshCookie = cookieFrom(loginResult, "nexzian_refresh");
        setStatus(tenantA, "SUSPENDED");

        Cookie csrf = csrfCookie();
        mockMvc.perform(post("/api/v1/auth/logout")
                .cookie(refreshCookie, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent());

        // Logout revokes the presented token as it always did.
        List<RefreshToken> tokens = tokensOf(ownerA);
        assertEquals(1, tokens.size());
        assertNotNull(tokens.get(0).getRevokedAt());
    }

    // 11 --------------------------------------------------------------------------

    @Test
    void publicWebsiteIsHiddenWhileSuspendedAndVisibleWhenActive() throws Exception {
        Property published = propertyRepository.findAllByTenantId(tenantA.getId()).get(0);

        mockMvc.perform(get("/api/v1/tenants/public/{slug}", "alpha-realty"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/public/{slug}/properties", "alpha-realty"))
                .andExpect(status().isOk());

        setStatus(tenantA, "SUSPENDED");

        mockMvc.perform(get("/api/v1/tenants/public/{slug}", "alpha-realty"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/public/{slug}/properties", "alpha-realty"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/public/{slug}/properties/{id}", "alpha-realty", published.getId()))
                .andExpect(status().isNotFound());

        // The other agency's public site is untouched.
        mockMvc.perform(get("/api/v1/tenants/public/{slug}", "beta-homes"))
                .andExpect(status().isOk());
    }

    // helpers ---------------------------------------------------------------------

    private List<RefreshToken> tokensOf(User user) {
        return refreshTokenRepository.findAll().stream()
                .filter(token -> user.getId().equals(token.getUserId()))
                .toList();
    }

    private void setStatus(Tenant tenant, String status) {
        Tenant stored = tenantRepository.findById(tenant.getId()).orElseThrow();
        stored.setStatus(status);
        tenantRepository.saveAndFlush(stored);
    }

    private ResultActions login(String email, String password) throws Exception {
        Cookie csrf = csrfCookie();
        return mockMvc.perform(post("/api/v1/auth/login")
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions refresh(Cookie refreshCookie) throws Exception {
        Cookie csrf = csrfCookie();
        return mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(refreshCookie, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()));
    }

    private Tenant createTenant(String name, String slug) {
        Tenant tenant = new Tenant();
        tenant.setBusinessName(name);
        tenant.setWebsiteSlug(slug);
        tenant.setEmail(slug + "@tenant.example");
        tenant.setCity("Hyderabad");
        return tenantRepository.save(tenant);
    }

    private User createUser(String email, UUID tenantId, String role) {
        User user = new User();
        user.setTenantId(tenantId);
        user.setFullName(email);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setStatus("ACTIVE");
        return userRepository.save(user);
    }

    private Property saveProperty(String title, UUID tenantId, String status) {
        Property property = new Property();
        property.setTenantId(tenantId);
        property.setTitle(title);
        property.setDescription("Test property");
        property.setTransactionType("SALE");
        property.setPropertyCategory("RESIDENTIAL");
        property.setPropertyType("APARTMENT");
        property.setCity("Hyderabad");
        property.setPrice(new BigDecimal("100000"));
        property.setAreaSqft(new BigDecimal("1000"));
        property.setBedrooms(2);
        property.setBathrooms(2);
        property.setStatus(status);
        return propertyRepository.save(property);
    }

    private Cookie csrfCookie() throws Exception {
        return mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
    }

    private Cookie cookieFrom(MvcResult result, String name) {
        String setCookie = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "="))
                .findFirst()
                .orElseThrow();
        return new Cookie(name, setCookie.substring(name.length() + 1, setCookie.indexOf(';')));
    }
}
