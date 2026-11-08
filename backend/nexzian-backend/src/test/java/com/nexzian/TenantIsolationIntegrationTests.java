package com.nexzian;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;

import com.nexzian.entity.Property;
import com.nexzian.entity.Tenant;
import com.nexzian.entity.User;
import com.nexzian.repository.PropertyRepository;
import com.nexzian.repository.TenantRepository;
import com.nexzian.repository.UserRepository;
import com.nexzian.security.JwtService;
import com.nexzian.security.LoginAttemptLimiter;

@SpringBootTest
@AutoConfigureMockMvc
class TenantIsolationIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PropertyRepository propertyRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private LoginAttemptLimiter loginAttemptLimiter;

    private String agencyAToken;
    private String agencyAAgentToken;
    private User agencyBUser;
    private Property agencyBProperty;

    @BeforeEach
    void setUp() {
        loginAttemptLimiter.clearAll();
        propertyRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();

        // Users must belong to real, ACTIVE tenants: users whose tenant row is
        // missing are denied by TenantAccessPolicy.
        User agencyAUser = createOwner("agency-a@example.com", createTenant("Agency A").getId());
        agencyBUser = createOwner("agency-b@example.com", createTenant("Agency B").getId());
        agencyAToken = jwtService.generateToken(agencyAUser);
        agencyAAgentToken = jwtService.generateToken(
                createUser("agency-a-agent@example.com", agencyAUser.getTenantId(), "AGENT"));

        saveProperty("Agency A Property", agencyAUser.getTenantId());
        agencyBProperty = saveProperty("Agency B Property", agencyBUser.getTenantId());
    }

    @Test
    void agencyTokenListsOnlyItsOwnPropertiesEvenWhenAnotherTenantIdIsSupplied() throws Exception {
        mockMvc.perform(get("/api/v1/properties")
                .queryParam("tenantId", agencyBProperty.getTenantId().toString())
                .header("Authorization", "Bearer " + agencyAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("Agency A Property"));
    }

    @Test
    void httpOnlyAccessCookieAuthenticatesProtectedRequests() throws Exception {
        mockMvc.perform(get("/api/v1/properties")
                .cookie(new Cookie("nexzian_access", agencyAToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void loginSetsHttpOnlyCookieWithoutReturningTheTokenInJson() throws Exception {
        Cookie csrfCookie = csrfCookie();
        mockMvc.perform(post("/api/v1/auth/login")
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"agency-a@example.com\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")));
    }

    @Test
    void refreshTokenIsRotatedAndCannotBeReused() throws Exception {
        Cookie csrfCookie = csrfCookie();
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"agency-a@example.com\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie refreshCookie = cookieFrom(login, "nexzian_refresh");
        Cookie refreshCsrfCookie = csrfCookie();
        MvcResult refresh = mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(refreshCookie, refreshCsrfCookie)
                .header("X-XSRF-TOKEN", refreshCsrfCookie.getValue()))
                .andExpect(status().isNoContent())
                .andReturn();
        assertTrue(refresh.getResponse().getHeaders("Set-Cookie").stream()
                .anyMatch(value -> value.startsWith("nexzian_refresh=")));

        Cookie reusedCsrfCookie = csrfCookie();
        mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(refreshCookie, reusedCsrfCookie)
                .header("X-XSRF-TOKEN", reusedCsrfCookie.getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void agencyTokenCannotUpdateAnotherTenantsProperty() throws Exception {
        Cookie csrfCookie = csrfCookie();
        mockMvc.perform(put("/api/v1/properties/{id}", agencyBProperty.getId())
                .header("Authorization", "Bearer " + agencyAToken)
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(propertyPayload("Attempted cross-tenant update")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousRequestReceivesJsonUnauthorizedResponse() throws Exception {
        mockMvc.perform(get("/api/v1/properties"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    void authenticatedNonOwnerReceivesJsonForbiddenResponse() throws Exception {
        mockMvc.perform(get("/api/v1/properties")
                .header("Authorization", "Bearer " + agencyAAgentToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    void agencyTokenListsOnlyItsOwnUsersEvenWhenAnotherTenantIdIsSupplied() throws Exception {
        mockMvc.perform(get("/api/v1/users")
                .queryParam("tenantId", agencyBUser.getTenantId().toString())
                .header("Authorization", "Bearer " + agencyAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.email == 'agency-b@example.com')]").isEmpty());
    }

    @Test
    void agencyTokenCannotReadAnotherTenantsUser() throws Exception {
        mockMvc.perform(get("/api/v1/users/{id}", agencyBUser.getId())
                .header("Authorization", "Bearer " + agencyAToken))
                .andExpect(status().isNotFound());
    }

    private Tenant createTenant(String businessName) {
        Tenant tenant = new Tenant();
        tenant.setBusinessName(businessName);
        tenant.setEmail(businessName.toLowerCase().replace(' ', '-') + "@tenant.example");
        tenant.setWebsiteSlug(businessName.toLowerCase().replace(' ', '-'));
        return tenantRepository.save(tenant);
    }

    private User createOwner(String email, UUID tenantId) {
        return createUser(email, tenantId, "OWNER");
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

    private Property saveProperty(String title, UUID tenantId) {
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
        property.setStatus("AVAILABLE");
        return propertyRepository.save(property);
    }

    private String propertyPayload(String title) {
        return """
                {"title":"%s","description":"Test property","transactionType":"SALE",
                "propertyCategory":"RESIDENTIAL","propertyType":"APARTMENT","city":"Hyderabad",
                "price":100000,"areaSqft":1000,"bedrooms":2,"bathrooms":2,"status":"AVAILABLE"}
                """.formatted(title);
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
