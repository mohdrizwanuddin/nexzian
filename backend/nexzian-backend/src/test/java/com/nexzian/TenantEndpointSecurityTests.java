package com.nexzian;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.nexzian.entity.Tenant;
import com.nexzian.entity.User;
import com.nexzian.repository.PropertyRepository;
import com.nexzian.repository.TenantRepository;
import com.nexzian.repository.UserRepository;
import com.nexzian.security.JwtService;

import jakarta.servlet.http.Cookie;

/**
 * Proves that tenant records are not exposed publicly or across agencies,
 * while agency registration and public agency websites keep working.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TenantEndpointSecurityTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private PropertyRepository propertyRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private Tenant tenantA;
    private Tenant tenantB;
    private String ownerAToken;
    private String agentAToken;
    private String platformAdminToken;

    @BeforeEach
    void setUp() {
        propertyRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();

        tenantA = createTenant("Alpha Realty", "alpha-realty", "alpha-secret@example.com");
        tenantB = createTenant("Beta Homes", "beta-homes", "beta-secret@example.com");

        ownerAToken = jwtService.generateToken(
                createUser("owner-a@example.com", tenantA.getId(), "OWNER"));
        agentAToken = jwtService.generateToken(
                createUser("agent-a@example.com", tenantA.getId(), "AGENT"));
        createUser("owner-b@example.com", tenantB.getId(), "OWNER");
        platformAdminToken = jwtService.generateToken(
                createUser("admin@example.com", new UUID(0L, 0L), "PLATFORM_ADMIN"));
    }

    // ---- S1: GET /api/v1/tenants -------------------------------------------------

    @Test
    void anonymousCannotListTenants() throws Exception {
        mockMvc.perform(get("/api/v1/tenants"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void agencyOwnerCannotListTenants() throws Exception {
        mockMvc.perform(get("/api/v1/tenants")
                .header("Authorization", "Bearer " + ownerAToken))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("beta-secret@example.com"))));
    }

    @Test
    void platformAdminCanListTenants() throws Exception {
        mockMvc.perform(get("/api/v1/tenants")
                .header("Authorization", "Bearer " + platformAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    // ---- S3: GET /api/v1/tenants/{id} --------------------------------------------

    @Test
    void anonymousCannotReadTenantById() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/{id}", tenantA.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownerCanReadOwnTenant() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/{id}", tenantA.getId())
                .header("Authorization", "Bearer " + ownerAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessName").value("Alpha Realty"));
    }

    @Test
    void ownerGetsNotFoundForAnotherAgencysTenant() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/{id}", tenantB.getId())
                .header("Authorization", "Bearer " + ownerAToken))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("Beta Homes"))))
                .andExpect(content().string(not(containsString("beta-secret@example.com"))));
    }

    @Test
    void ownerGetsSameNotFoundForNonExistentTenant() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + ownerAToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void agentCannotReadTenantById() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/{id}", tenantA.getId())
                .header("Authorization", "Bearer " + agentAToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void platformAdminCanReadAnyTenant() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/{id}", tenantB.getId())
                .header("Authorization", "Bearer " + platformAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessName").value("Beta Homes"));
    }

    // ---- Existing behaviour that must be preserved -------------------------------

    @Test
    void publicAgencyProfileStillWorksAndHidesPrivateFields() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/public/{slug}", "alpha-realty"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessName").value("Alpha Realty"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.subscriptionPlan").doesNotExist());
    }

    @Test
    void publicPropertyListingStillWorksWithoutLogin() throws Exception {
        mockMvc.perform(get("/api/v1/public/{slug}/properties", "alpha-realty"))
                .andExpect(status().isOk());
    }

    @Test
    void unknownPublicAgencyIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/public/{slug}", "does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void legacyPublicTenantRegistrationEndpointIsNoLongerPublic() throws Exception {
        Cookie csrf = csrfCookie();
        mockMvc.perform(post("/api/v1/tenants")
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessName\":\"Gamma Estates\",\"email\":\"gamma@example.com\","
                        + "\"phone\":\"9999999999\",\"city\":\"Hyderabad\",\"locality\":\"Gachibowli\"}"))
                .andExpect(status().isUnauthorized());

        org.junit.jupiter.api.Assertions.assertFalse(
                tenantRepository.existsByEmailIgnoreCase("gamma@example.com"));
    }

    // ---- PLATFORM_ADMIN cannot be obtained through registration ------------------

    @Test
    void legacyPublicUserRegistrationEndpointIsNoLongerPublic() throws Exception {
        Tenant emptyTenant = createTenant("Delta Props", "delta-props", "delta@example.com");
        Cookie csrf = csrfCookie();

        mockMvc.perform(post("/api/v1/users/register")
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"" + emptyTenant.getId() + "\",\"fullName\":\"Mallory\","
                        + "\"email\":\"mallory@example.com\",\"password\":\"password-123\","
                        + "\"role\":\"PLATFORM_ADMIN\"}"))
                .andExpect(status().isUnauthorized());

        org.junit.jupiter.api.Assertions.assertTrue(
                userRepository.findByEmail("mallory@example.com").isEmpty());
    }

    @Test
    void ownerCannotCreateTeamMemberWithPlatformAdminRole() throws Exception {
        Cookie csrf = csrfCookie();

        mockMvc.perform(post("/api/v1/users/team")
                .header("Authorization", "Bearer " + ownerAToken)
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Mallory\",\"email\":\"mallory2@example.com\","
                        + "\"password\":\"password-123\",\"role\":\"PLATFORM_ADMIN\"}"))
                .andExpect(status().is4xxClientError());

        org.junit.jupiter.api.Assertions.assertTrue(
                userRepository.findByEmail("mallory2@example.com").isEmpty());
    }

    // ---- helpers ----------------------------------------------------------------

    private Tenant createTenant(String name, String slug, String email) {
        Tenant tenant = new Tenant();
        tenant.setBusinessName(name);
        tenant.setWebsiteSlug(slug);
        tenant.setEmail(email);
        tenant.setCity("Hyderabad");
        return tenantRepository.save(tenant);
    }

    private User createUser(String email, UUID tenantId, String role) {
        User user = new User();
        user.setTenantId(tenantId);
        user.setFullName(email);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode("test-password"));
        user.setRole(role);
        user.setStatus("ACTIVE");
        return userRepository.save(user);
    }

    private Cookie csrfCookie() throws Exception {
        return mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
    }
}
