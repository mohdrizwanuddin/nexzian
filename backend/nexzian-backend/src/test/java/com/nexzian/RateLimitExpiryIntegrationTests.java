package com.nexzian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
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

/**
 * End-to-end expiry of the throttles using a controllable clock injected into the real
 * Spring beans. Own context and own in-memory database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RateLimitExpiryIntegrationTests.ClockConfig.class)
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:nexzian-expiry-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
class RateLimitExpiryIntegrationTests {

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    private static final String PASSWORD = "Correct-password1";
    private static final String OWNER = "owner@expiry.example";

    @Autowired private MockMvc mockMvc;
    @Autowired private MutableClock clock;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private PropertyRepository propertyRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private LoginAttemptLimiter loginAttemptLimiter;
    @Autowired private RegistrationRateLimiter registrationRateLimiter;

    @BeforeEach
    void setUp() {
        loginAttemptLimiter.clearAll();
        registrationRateLimiter.clearAll();
        propertyRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();

        Tenant tenant = new Tenant();
        tenant.setBusinessName("Expiry Realty");
        tenant.setWebsiteSlug("expiry-realty");
        tenant.setEmail("expiry@tenant.example");
        tenant = tenantRepository.save(tenant);

        User user = new User();
        user.setTenantId(tenant.getId());
        user.setFullName("Owner");
        user.setEmail(OWNER);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole("OWNER");
        user.setStatus("ACTIVE");
        userRepository.save(user);
    }

    @Test
    void loginLockShrinksOverTimeAndEndsAfterFifteenMinutes() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(OWNER, "wrong").andExpect(status().isUnauthorized());
        }

        MvcResult locked = login(OWNER, PASSWORD).andExpect(status().isTooManyRequests()).andReturn();
        assertEquals("900", locked.getResponse().getHeader("Retry-After"));

        clock.advance(Duration.ofMinutes(5));
        MvcResult stillLocked = login(OWNER, PASSWORD).andExpect(status().isTooManyRequests()).andReturn();
        assertEquals("600", stillLocked.getResponse().getHeader("Retry-After"));

        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        login(OWNER, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void failuresOutsideTheFifteenMinuteWindowDoNotAccumulate() throws Exception {
        for (int i = 0; i < 4; i++) {
            login(OWNER, "wrong").andExpect(status().isUnauthorized());
        }
        clock.advance(Duration.ofMinutes(16));

        for (int i = 0; i < 4; i++) { // would be the 5th..8th failure without expiry
            login(OWNER, "wrong").andExpect(status().isUnauthorized());
        }
        login(OWNER, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void registrationBlockEndsAfterOneHour() throws Exception {
        for (int i = 0; i < 5; i++) {
            register("Agency " + i, "agency" + i + "@expiry.example").andExpect(status().isCreated());
        }

        MvcResult blocked = register("Agency 6", "agency6@expiry.example")
                .andExpect(status().isTooManyRequests()).andReturn();
        assertEquals("3600", blocked.getResponse().getHeader("Retry-After"));

        clock.advance(Duration.ofMinutes(59));
        register("Agency 6", "agency6@expiry.example").andExpect(status().isTooManyRequests());

        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        register("Agency 6", "agency6@expiry.example").andExpect(status().isCreated());
    }

    private ResultActions login(String email, String password) throws Exception {
        Cookie csrf = csrfCookie();
        return mockMvc.perform(post("/api/v1/auth/login")
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions register(String businessName, String email) throws Exception {
        Cookie csrf = csrfCookie();
        return mockMvc.perform(post("/api/v1/auth/register-agency")
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessName\":\"" + businessName + "\",\"fullName\":\"Owner\","
                        + "\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"));
    }

    private Cookie csrfCookie() throws Exception {
        return mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
    }
}
