package com.nexzian;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.nexzian.entity.Tenant;
import com.nexzian.entity.User;
import com.nexzian.repository.PropertyRepository;
import com.nexzian.repository.TenantRepository;
import com.nexzian.repository.UserRepository;
import com.nexzian.security.LoginAttemptLimiter;
import com.nexzian.security.RegistrationRateLimiter;

import jakarta.servlet.http.Cookie;

/** HTTP-level behaviour of the login and registration throttles (status codes, headers, bodies). */
@SpringBootTest
@AutoConfigureMockMvc
class RateLimitIntegrationTests {

    private static final String PASSWORD = "Correct-password1";
    private static final String OWNER = "owner@rate.example";
    private static final String IP = "203.0.113.10";
    private static final String OTHER_IP = "198.51.100.77";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private PropertyRepository propertyRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private LoginAttemptLimiter loginAttemptLimiter;
    @Autowired private RegistrationRateLimiter registrationRateLimiter;

    private Tenant activeTenant;
    private Tenant suspendedTenant;

    @BeforeEach
    void setUp() {
        loginAttemptLimiter.clearAll();
        registrationRateLimiter.clearAll();
        propertyRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();

        activeTenant = createTenant("Rate Realty", "rate-realty", "ACTIVE");
        suspendedTenant = createTenant("Frozen Homes", "frozen-homes", "SUSPENDED");

        createUser(OWNER, activeTenant.getId(), "ACTIVE");
        createUser("inactive@rate.example", activeTenant.getId(), "INACTIVE");
        createUser("frozen@rate.example", suspendedTenant.getId(), "ACTIVE");
    }

    // ---- login: lock, status codes, headers ----------------------------------------------------

    @Test
    void fiveWrongPasswordsLockTheKeyEvenForTheCorrectPasswordWithRetryAfter() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(OWNER, "wrong-" + i, IP)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Invalid email or password"));
        }

        MvcResult locked = login(OWNER, PASSWORD, IP)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.message").value(containsString("Too many failed login attempts")))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();

        long retryAfter = Long.parseLong(locked.getResponse().getHeader("Retry-After"));
        assertTrue(retryAfter > 0 && retryAfter <= 15 * 60, "Retry-After seconds: " + retryAfter);
    }

    @Test
    void wrongPasswordAndUnknownEmailReturnTheSameGeneric401() throws Exception {
        MvcResult wrongPassword = login(OWNER, "nope", IP).andExpect(status().isUnauthorized()).andReturn();
        MvcResult unknownEmail = login("nobody@rate.example", "nope", IP)
                .andExpect(status().isUnauthorized()).andReturn();

        assertEquals(bodyField(wrongPassword, "$.message"), bodyField(unknownEmail, "$.message"));
        assertEquals(bodyField(wrongPassword, "$.error"), bodyField(unknownEmail, "$.error"));
        assertEquals("Invalid email or password", bodyField(unknownEmail, "$.message"));
    }

    @Test
    void lockResponseIsIdenticalWhetherOrNotTheEmailExists() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(OWNER, "wrong", IP).andExpect(status().isUnauthorized());
            login("ghost@rate.example", "wrong", IP).andExpect(status().isUnauthorized());
        }

        MvcResult existing = login(OWNER, PASSWORD, IP).andExpect(status().isTooManyRequests()).andReturn();
        MvcResult unknown = login("ghost@rate.example", PASSWORD, IP)
                .andExpect(status().isTooManyRequests()).andReturn();

        assertEquals(bodyField(existing, "$.message"), bodyField(unknown, "$.message"));
        assertEquals(bodyField(existing, "$.error"), bodyField(unknown, "$.error"));
        assertTrue(Math.abs(Long.parseLong(existing.getResponse().getHeader("Retry-After"))
                - Long.parseLong(unknown.getResponse().getHeader("Retry-After"))) <= 1);
    }

    @Test
    void unknownEmailsCountAsFailedAttempts() throws Exception {
        for (int i = 0; i < 5; i++) {
            login("ghost@rate.example", "wrong", IP).andExpect(status().isUnauthorized());
        }
        login("ghost@rate.example", "wrong", IP).andExpect(status().isTooManyRequests());
    }

    @Test
    void emailCasingVariantsShareOneCounter() throws Exception {
        // Five different casings = five failures on ONE normalized key.
        login("Owner@Rate.Example", "wrong", IP).andExpect(status().isUnauthorized());
        login("owner@rate.example", "wrong", IP).andExpect(status().isUnauthorized());
        login("OWNER@RATE.EXAMPLE", "wrong", IP).andExpect(status().isUnauthorized());
        login("oWNER@rate.example", "wrong", IP).andExpect(status().isUnauthorized());
        login("owner@RATE.example", "wrong", IP).andExpect(status().isUnauthorized());

        login("owner@rate.example", PASSWORD, IP).andExpect(status().isTooManyRequests());
    }

    @Test
    void aLockedKeyDoesNotAffectAnotherIp() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(OWNER, "wrong", IP).andExpect(status().isUnauthorized());
        }
        login(OWNER, PASSWORD, IP).andExpect(status().isTooManyRequests());

        login(OWNER, PASSWORD, OTHER_IP).andExpect(status().isOk());
    }

    // ---- login: success handling ------------------------------------------------------------------

    @Test
    void successfulLoginClearsTheEmailIpCounter() throws Exception {
        for (int i = 0; i < 4; i++) {
            login(OWNER, "wrong", IP).andExpect(status().isUnauthorized());
        }
        login(OWNER, PASSWORD, IP).andExpect(status().isOk());

        // The 4 earlier failures were forgotten: five new failures are tolerated, the sixth is not.
        for (int i = 0; i < 5; i++) {
            login(OWNER, "wrong", IP).andExpect(status().isUnauthorized());
        }
        login(OWNER, "wrong", IP).andExpect(status().isTooManyRequests());
    }

    @Test
    void successfulLoginDoesNotClearTheAggregateIpCounter() throws Exception {
        for (int i = 0; i < 25; i++) {
            login("ghost" + i + "@rate.example", "wrong", IP).andExpect(status().isUnauthorized());
        }
        login(OWNER, PASSWORD, IP).andExpect(status().isOk());
        for (int i = 25; i < 30; i++) {
            login("ghost" + i + "@rate.example", "wrong", IP).andExpect(status().isUnauthorized());
        }

        // 30 failures from the IP are on record despite the successful login in between.
        login(OWNER, PASSWORD, IP).andExpect(status().isTooManyRequests());
    }

    @Test
    void thirtyFailuresFromOneIpLockEveryEmailOnThatIpButNotOtherIps() throws Exception {
        for (int i = 0; i < 30; i++) {
            login("ghost" + i + "@rate.example", "wrong", IP).andExpect(status().isUnauthorized());
        }

        login(OWNER, PASSWORD, IP)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        login(OWNER, PASSWORD, OTHER_IP).andExpect(status().isOk());
    }

    // ---- login: account state never revealed without the password ------------------------------

    @Test
    void inactiveUserWithWrongPasswordGetsTheGeneric401() throws Exception {
        login("inactive@rate.example", "wrong", IP)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"))
                .andExpect(content().string(not(containsString("inactive"))));
    }

    @Test
    void inactiveUserWithCorrectPasswordGets403AndIsNeverCountedAsAFailure() throws Exception {
        for (int i = 0; i < 12; i++) { // more than both thresholds
            login("inactive@rate.example", PASSWORD, IP)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message")
                            .value("Your account is inactive. Please contact your administrator."))
                    .andExpect(header().doesNotExist("Set-Cookie"));
        }
        login(OWNER, PASSWORD, IP).andExpect(status().isOk());
    }

    @Test
    void suspendedTenantKeepsItsS4_403AndIsNeverCountedAsAFailure() throws Exception {
        for (int i = 0; i < 12; i++) {
            login("frozen@rate.example", PASSWORD, IP)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(containsString("suspended")))
                    .andExpect(header().doesNotExist("Set-Cookie"));
        }
        login("frozen@rate.example", "wrong", IP).andExpect(status().isUnauthorized());
    }

    /**
     * BCrypt's matches() truncates the candidate to 72 bytes, so without a guard a stored
     * 72-byte password would also be accepted when followed by extra characters.
     */
    @Test
    void storedPasswordFollowedByExtraCharactersDoesNotAuthenticateAndIsCounted() throws Exception {
        String stored72 = "a".repeat(72); // exactly 72 ASCII bytes: the longest BCrypt can store
        createUser("trunc@rate.example", activeTenant.getId(), "ACTIVE", stored72);

        // Baseline: the exact stored password works (and clears the email+IP counter).
        login("trunc@rate.example", stored72, IP)
                .andExpect(status().isOk())
                .andExpect(header().exists("Set-Cookie"));

        // The same password plus extra characters must NOT authenticate: generic 401, no session.
        for (String longer : List.of(stored72 + "b", stored72 + "extra-characters", "a".repeat(100))) {
            login("trunc@rate.example", longer, IP)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Invalid email or password"))
                    .andExpect(header().doesNotExist("Set-Cookie"));
        }

        // Those 3 refusals were counted as failed attempts: two more fill the allowance of 5,
        // after which even the CORRECT password is locked out.
        login("trunc@rate.example", stored72 + "c", IP).andExpect(status().isUnauthorized());
        login("trunc@rate.example", stored72 + "d", IP).andExpect(status().isUnauthorized());
        login("trunc@rate.example", stored72, IP)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void overlongPasswordForAnUnknownEmailGetsTheSameGeneric401() throws Exception {
        login("ghost@rate.example", "x".repeat(80), IP)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    // ---- login: simultaneous attempts ----------------------------------------------------------------

    @Test
    void simultaneousWrongGuessesCannotExceedTheFiveAttemptLimit() throws Exception {
        int threads = 30;
        List<Cookie> csrfCookies = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            csrfCookies.add(csrfCookie());
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Cookie csrf = csrfCookies.get(i);
            results.add(executor.submit(() -> {
                start.await();
                return mockMvc.perform(post("/api/v1/auth/login")
                        .with(request -> { request.setRemoteAddr(IP); return request; })
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + OWNER + "\",\"password\":\"guess\"}"))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();

        int unauthorized = 0;
        int tooMany = 0;
        for (Future<Integer> result : results) {
            int code = result.get();
            if (code == 401) {
                unauthorized++;
            } else if (code == 429) {
                tooMany++;
            }
        }
        executor.shutdown();

        assertTrue(unauthorized >= 1 && unauthorized <= 5,
                "at most 5 guesses may be evaluated, but " + unauthorized + " were");
        assertEquals(threads, unauthorized + tooMany, "every request must be 401 or 429");
        login(OWNER, PASSWORD, IP).andExpect(status().isTooManyRequests());
    }

    // ---- registration throttle ----------------------------------------------------------------------------

    @Test
    void sixthRegistrationAttemptFromOneIpIsRejectedWith429AndRetryAfter() throws Exception {
        for (int i = 0; i < 5; i++) {
            register("Agency " + i, "agency" + i + "@reg.example", PASSWORD, IP)
                    .andExpect(status().isCreated());
        }

        MvcResult blocked = register("Agency 6", "agency6@reg.example", PASSWORD, IP)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value(containsString("Too many registration attempts")))
                .andReturn();

        long retryAfter = Long.parseLong(blocked.getResponse().getHeader("Retry-After"));
        assertTrue(retryAfter > 0 && retryAfter <= 3600, "Retry-After seconds: " + retryAfter);
        assertEquals(5, tenantRepository.findAll().stream()
                .filter(t -> t.getEmail().endsWith("@reg.example")).count(),
                "the blocked attempt must not create anything");
    }

    @Test
    void registrationLimitIsPerIp() throws Exception {
        for (int i = 0; i < 5; i++) {
            register("Agency " + i, "agency" + i + "@reg.example", PASSWORD, IP)
                    .andExpect(status().isCreated());
        }
        register("Agency 6", "agency6@reg.example", PASSWORD, IP).andExpect(status().isTooManyRequests());

        register("Agency 7", "agency7@reg.example", PASSWORD, OTHER_IP).andExpect(status().isCreated());
    }

    @Test
    void invalidRegistrationRequestsFailValidationAndAreNotCounted() throws Exception {
        for (int i = 0; i < 12; i++) {
            register("Bad " + i, "bad" + i + "@reg.example", "short", IP)
                    .andExpect(status().isBadRequest());
        }

        for (int i = 0; i < 5; i++) {
            register("Agency " + i, "agency" + i + "@reg.example", PASSWORD, IP)
                    .andExpect(status().isCreated());
        }
    }

    @Test
    void duplicateRegistrationAttemptsDoCountTowardTheLimit() throws Exception {
        register("Agency 0", "agency0@reg.example", PASSWORD, IP).andExpect(status().isCreated());
        for (int i = 0; i < 4; i++) {
            register("Agency 0", "agency0@reg.example", PASSWORD, IP).andExpect(status().isConflict());
        }

        register("Agency X", "agencyx@reg.example", PASSWORD, IP).andExpect(status().isTooManyRequests());
    }

    @Test
    void registrationNeverCountsAsAFailedLoginAttempt() throws Exception {
        for (int i = 0; i < 5; i++) {
            register("Agency " + i, "agency" + i + "@reg.example", PASSWORD, IP)
                    .andExpect(status().isCreated());
        }

        // Five registrations from this IP leave the login counters untouched:
        for (int i = 0; i < 5; i++) {
            login(OWNER, "wrong", IP).andExpect(status().isUnauthorized());
        }
        login(OWNER, "wrong", IP).andExpect(status().isTooManyRequests()); // only now, after 5 real failures
        login(OWNER, PASSWORD, OTHER_IP).andExpect(status().isOk());
        assertNotEquals(0, tenantRepository.count());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private String bodyField(MvcResult result, String path) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    private ResultActions login(String email, String password, String ip) throws Exception {
        Cookie csrf = csrfCookie();
        return mockMvc.perform(post("/api/v1/auth/login")
                .with(request -> { request.setRemoteAddr(ip); return request; })
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions register(String businessName, String email, String password, String ip)
            throws Exception {
        Cookie csrf = csrfCookie();
        return mockMvc.perform(post("/api/v1/auth/register-agency")
                .with(request -> { request.setRemoteAddr(ip); return request; })
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessName\":\"" + businessName + "\",\"fullName\":\"Owner\","
                        + "\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private Tenant createTenant(String name, String slug, String status) {
        Tenant tenant = new Tenant();
        tenant.setBusinessName(name);
        tenant.setWebsiteSlug(slug);
        tenant.setEmail(slug + "@tenant.example");
        tenant.setStatus(status);
        return tenantRepository.save(tenant);
    }

    private User createUser(String email, UUID tenantId, String status) {
        return createUser(email, tenantId, status, PASSWORD);
    }

    private User createUser(String email, UUID tenantId, String status, String password) {
        User user = new User();
        user.setTenantId(tenantId);
        user.setFullName(email);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole("OWNER");
        user.setStatus(status);
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
