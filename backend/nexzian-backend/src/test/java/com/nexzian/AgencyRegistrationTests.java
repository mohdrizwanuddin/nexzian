package com.nexzian;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.nexzian.entity.Tenant;
import com.nexzian.entity.User;
import com.nexzian.repository.PropertyRepository;
import com.nexzian.repository.TenantRepository;
import com.nexzian.repository.UserRepository;
import com.nexzian.security.JwtService;

import jakarta.servlet.http.Cookie;

/** Covers POST /api/v1/auth/register-agency and the removal of the old public endpoints. */
@SpringBootTest
@AutoConfigureMockMvc
class AgencyRegistrationTests {

    private static final String PASSWORD = "Str0ng-password";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private PropertyRepository propertyRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    @BeforeEach
    void setUp() {
        propertyRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();
    }

    // ---- Legitimate registration ------------------------------------------------

    @Test
    void registrationCreatesTenantAndExactlyOneOwnerWithHashedPassword() throws Exception {
        register(body("Sunrise Realty", "owner@sunrise.example", PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.businessName").value("Sunrise Realty"))
                .andExpect(jsonPath("$.websiteSlug").value("sunrise-realty"))
                .andExpect(jsonPath("$.email").value("owner@sunrise.example"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(content().string(not(containsString(PASSWORD))));

        Tenant tenant = tenantRepository.findByEmail("owner@sunrise.example").orElseThrow();
        assertEquals("ACTIVE", tenant.getStatus());
        assertEquals("FREE", tenant.getSubscriptionPlan());

        List<User> users = userRepository.findAllByTenantId(tenant.getId());
        assertEquals(1, users.size());
        User owner = users.get(0);
        assertEquals("OWNER", owner.getRole());
        assertEquals("ACTIVE", owner.getStatus());
        assertNotEquals(PASSWORD, owner.getPassword());
        assertTrue(passwordEncoder.matches(PASSWORD, owner.getPassword()));
    }

    @Test
    void newOwnerCanLogInAndPublicAgencySiteIsAvailable() throws Exception {
        register(body("Sunrise Realty", "owner@sunrise.example", PASSWORD))
                .andExpect(status().isCreated());

        Cookie csrf = csrfCookie();
        mockMvc.perform(post("/api/v1/auth/login")
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"owner@sunrise.example\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));

        mockMvc.perform(get("/api/v1/tenants/public/{slug}", "sunrise-realty"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessName").value("Sunrise Realty"));
        mockMvc.perform(get("/api/v1/public/{slug}/properties", "sunrise-realty"))
                .andExpect(status().isOk());
    }

    // ---- Email casing: documents CURRENT behaviour --------------------------------

    /**
     * Documents (and pins) today's intentional behaviour:
     *  - registration stores the email in exactly the casing supplied;
     *  - registration treats other casings of that email as duplicates;
     *  - login is case-sensitive and unchanged, so it needs the registered casing.
     * If login is ever made case-insensitive, change the last assertion deliberately.
     */
    @Test
    void emailCasingBehaviourRegistrationIsCaseInsensitiveButLoginIsCaseSensitive() throws Exception {
        register(body("Casing Realty", "Owner@Casing.Example", PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("Owner@Casing.Example"));

        // Stored exactly as supplied.
        assertEquals("Owner@Casing.Example",
                userRepository.findAll().get(0).getEmail());

        // Other casings are rejected as duplicates at registration.
        register(body("Another Agency", "owner@casing.example", PASSWORD))
                .andExpect(status().isConflict());
        assertEquals(1, userRepository.count());

        // Login with the exact registered casing works...
        login("Owner@Casing.Example").andExpect(status().isOk());
        // ...but login with a different casing does not (unchanged, case-sensitive).
        login("owner@casing.example").andExpect(status().isUnauthorized());
    }

    // ---- Privilege escalation attempts -------------------------------------------

    @Test
    void clientCannotChooseTenantRoleStatusOrPlan() throws Exception {
        Tenant victim = createTenant("Victim Homes", "victim-homes", "victim@example.com");

        String malicious = "{\"businessName\":\"Evil Realty\",\"fullName\":\"Mallory\","
                + "\"email\":\"mallory@example.com\",\"password\":\"" + PASSWORD + "\","
                + "\"role\":\"PLATFORM_ADMIN\",\"tenantId\":\"" + victim.getId() + "\","
                + "\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"INACTIVE\","
                + "\"subscriptionPlan\":\"ENTERPRISE\"}";

        register(malicious).andExpect(status().isCreated());

        User mallory = userRepository.findByEmail("mallory@example.com").orElseThrow();
        Tenant evil = tenantRepository.findByEmail("mallory@example.com").orElseThrow();

        assertEquals("OWNER", mallory.getRole());
        assertEquals("ACTIVE", mallory.getStatus());
        assertEquals(evil.getId(), mallory.getTenantId());
        assertNotEquals(victim.getId(), mallory.getTenantId());
        assertEquals("FREE", evil.getSubscriptionPlan());
        assertEquals("ACTIVE", evil.getStatus());
        assertTrue(userRepository.findAllByTenantId(victim.getId()).isEmpty(),
                "the existing tenant must not gain any user");
    }

    // ---- Unauthorized registration under an existing agency ----------------------

    @Test
    void anonymousCannotRegisterUnderAnExistingAgency() throws Exception {
        register(body("Sunrise Realty", "owner@sunrise.example", PASSWORD))
                .andExpect(status().isCreated());
        Tenant sunrise = tenantRepository.findByEmail("owner@sunrise.example").orElseThrow();
        long usersBefore = userRepository.count();

        Cookie csrf = csrfCookie();
        mockMvc.perform(post("/api/v1/users/register")
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"" + sunrise.getId() + "\",\"fullName\":\"Mallory\","
                        + "\"email\":\"mallory@example.com\",\"password\":\"" + PASSWORD + "\","
                        + "\"role\":\"OWNER\"}"))
                .andExpect(status().isUnauthorized());

        assertEquals(usersBefore, userRepository.count());
    }

    @Test
    void authenticatedOwnerCannotUseOldRegisterEndpointEither() throws Exception {
        register(body("Sunrise Realty", "owner@sunrise.example", PASSWORD))
                .andExpect(status().isCreated());
        Tenant sunrise = tenantRepository.findByEmail("owner@sunrise.example").orElseThrow();
        String ownerToken = jwtService.generateToken(
                userRepository.findByEmail("owner@sunrise.example").orElseThrow());
        long usersBefore = userRepository.count();

        Cookie csrf = csrfCookie();
        mockMvc.perform(post("/api/v1/users/register")
                .header("Authorization", "Bearer " + ownerToken)
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"" + sunrise.getId() + "\",\"fullName\":\"Mallory\","
                        + "\"email\":\"mallory@example.com\",\"password\":\"" + PASSWORD + "\","
                        + "\"role\":\"OWNER\"}"))
                .andExpect(status().is4xxClientError());

        assertEquals(usersBefore, userRepository.count());
    }

    // ---- Duplicates --------------------------------------------------------------

    @Test
    void duplicateEmailIsRejectedAndCreatesNothing() throws Exception {
        register(body("Sunrise Realty", "owner@sunrise.example", PASSWORD))
                .andExpect(status().isCreated());

        register(body("Another Name", "owner@sunrise.example", PASSWORD))
                .andExpect(status().isConflict());

        assertEquals(1, tenantRepository.count());
        assertEquals(1, userRepository.count());
    }

    @Test
    void duplicateEmailIsDetectedCaseInsensitively() throws Exception {
        register(body("Sunrise Realty", "owner@sunrise.example", PASSWORD))
                .andExpect(status().isCreated());

        register(body("Another Name", "OWNER@Sunrise.Example", PASSWORD))
                .andExpect(status().isConflict());

        assertEquals(1, tenantRepository.count());
        assertEquals(1, userRepository.count());
    }

    @Test
    void emailAlreadyUsedByAnotherAgencysUserIsRejectedWithoutCreatingATenant() throws Exception {
        Tenant existing = createTenant("Existing Homes", "existing-homes", "existing@example.com");
        createUser("staff@example.com", existing.getId(), "STAFF");

        register(body("Brand New", "staff@example.com", PASSWORD))
                .andExpect(status().isConflict());

        assertEquals(1, tenantRepository.count());
        assertTrue(tenantRepository.findByEmail("staff@example.com").isEmpty());
    }

    @Test
    void duplicateBusinessNameIsRejectedCaseInsensitivelyWithConflictNotServerError() throws Exception {
        register(body("Sunrise Realty", "owner@sunrise.example", PASSWORD))
                .andExpect(status().isCreated());

        register(body("SUNRISE REALTY", "second@example.com", PASSWORD))
                .andExpect(status().isConflict());

        assertEquals(1, tenantRepository.count());
        assertEquals(1, userRepository.count());
        assertTrue(userRepository.findByEmail("second@example.com").isEmpty());
    }

    @Test
    void concurrentRegistrationsOfTheSameAgencyCreateExactlyOne() throws Exception {
        int attempts = 2;
        List<Cookie> csrfCookies = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            csrfCookies.add(csrfCookie());
        }

        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            Cookie csrf = csrfCookies.get(i);
            results.add(executor.submit(() -> {
                start.await();
                return mockMvc.perform(post("/api/v1/auth/register-agency")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Race Realty", "race@example.com", PASSWORD)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }
        executor.shutdown();
        Collections.sort(statuses);

        assertEquals(List.of(201, 409), statuses);
        assertEquals(1, tenantRepository.count());
        assertEquals(1, userRepository.count());
    }

    // ---- Validation --------------------------------------------------------------

    @Test
    void shortPasswordIsRejectedAndLeavesNoTenantBehind() throws Exception {
        register(body("Sunrise Realty", "owner@sunrise.example", "short"))
                .andExpect(status().isBadRequest());

        assertEquals(0, tenantRepository.count());
        assertEquals(0, userRepository.count());
    }

    @Test
    void missingBusinessNameIsRejected() throws Exception {
        register("{\"fullName\":\"Owner\",\"email\":\"owner@sunrise.example\","
                + "\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isBadRequest());

        assertEquals(0, tenantRepository.count());
    }

    @Test
    void invalidEmailIsRejected() throws Exception {
        register(body("Sunrise Realty", "not-an-email", PASSWORD))
                .andExpect(status().isBadRequest());

        assertEquals(0, tenantRepository.count());
    }

    @Test
    void registrationRequiresCsrfToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register-agency")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("Sunrise Realty", "owner@sunrise.example", PASSWORD)))
                .andExpect(status().isForbidden());

        assertEquals(0, tenantRepository.count());
    }

    // ---- helpers -----------------------------------------------------------------

    private String body(String businessName, String email, String password) {
        return "{\"businessName\":\"" + businessName + "\",\"fullName\":\"Owner Name\","
                + "\"email\":\"" + email + "\",\"password\":\"" + password + "\","
                + "\"phone\":\"9999999999\",\"city\":\"Hyderabad\",\"locality\":\"Gachibowli\"}";
    }

    private ResultActions login(String email) throws Exception {
        Cookie csrf = csrfCookie();
        return mockMvc.perform(post("/api/v1/auth/login")
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"));
    }

    private ResultActions register(String json) throws Exception {
        Cookie csrf = csrfCookie();
        return mockMvc.perform(post("/api/v1/auth/register-agency")
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

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
