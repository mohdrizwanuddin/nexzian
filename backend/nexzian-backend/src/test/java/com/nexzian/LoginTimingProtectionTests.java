package com.nexzian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.nexzian.entity.Tenant;
import com.nexzian.entity.User;
import com.nexzian.repository.PropertyRepository;
import com.nexzian.repository.TenantRepository;
import com.nexzian.repository.UserRepository;
import com.nexzian.security.LoginAttemptLimiter;

import jakarta.servlet.http.Cookie;

/**
 * Login must do the same BCrypt work whether or not the email exists, and must check the
 * password before revealing anything about the account. Runs in its own context (spy bean)
 * with its own in-memory database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:nexzian-timing-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
class LoginTimingProtectionTests {

    private static final String PASSWORD = "Correct-password1";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private PropertyRepository propertyRepository;
    @Autowired private LoginAttemptLimiter loginAttemptLimiter;
    @MockitoSpyBean private PasswordEncoder passwordEncoder;

    private User activeUser;
    private User inactiveUser;

    @BeforeEach
    void setUp() {
        loginAttemptLimiter.clearAll();
        propertyRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();

        Tenant tenant = new Tenant();
        tenant.setBusinessName("Timing Realty");
        tenant.setWebsiteSlug("timing-realty");
        tenant.setEmail("timing@tenant.example");
        tenant = tenantRepository.save(tenant);

        activeUser = createUser("active@timing.example", tenant.getId(), "ACTIVE");
        inactiveUser = createUser("inactive@timing.example", tenant.getId(), "INACTIVE");

        Mockito.clearInvocations(passwordEncoder); // ignore the encode() calls above
    }

    @Test
    void unknownEmailStillRunsABcryptComparisonAgainstAWellFormedDummyHash() throws Exception {
        login("nobody@timing.example", "some-password").andExpect(status().isUnauthorized());

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder, times(1)).matches(eq("some-password"), hash.capture());

        // Well-formed BCrypt, and the same cost factor the application's encoder produces,
        // so the dummy comparison takes as long as a real one.
        String dummy = hash.getValue();
        assertTrue(dummy.matches("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$"), "not a BCrypt hash: " + dummy);
        String realHash = passwordEncoder.encode("probe");
        assertEquals(realHash.substring(0, 7), dummy.substring(0, 7),
                "dummy hash cost factor must equal the encoder's");
    }

    @Test
    void knownEmailWithWrongPasswordIsComparedAgainstItsOwnHash() throws Exception {
        login("active@timing.example", "wrong").andExpect(status().isUnauthorized());

        verify(passwordEncoder, times(1)).matches(eq("wrong"), eq(activeUser.getPassword()));
    }

    @Test
    void inactiveUserIsPasswordCheckedBeforeAnythingAboutItsStateIsRevealed() throws Exception {
        login("inactive@timing.example", "wrong").andExpect(status().isUnauthorized());
        verify(passwordEncoder, times(1)).matches(eq("wrong"), eq(inactiveUser.getPassword()));

        login("inactive@timing.example", PASSWORD).andExpect(status().isForbidden());
        verify(passwordEncoder, times(1)).matches(eq(PASSWORD), eq(inactiveUser.getPassword()));
    }

    @Test
    void aLockedAttemptDoesNoBcryptWorkAtAll() throws Exception {
        for (int i = 0; i < 5; i++) {
            login("active@timing.example", "wrong").andExpect(status().isUnauthorized());
        }
        Mockito.clearInvocations(passwordEncoder);

        login("active@timing.example", PASSWORD).andExpect(status().isTooManyRequests());

        verify(passwordEncoder, times(0)).matches(anyString(), anyString());
    }

    private ResultActions login(String email, String password) throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("XSRF-TOKEN");
        return mockMvc.perform(post("/api/v1/auth/login")
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private User createUser(String email, UUID tenantId, String status) {
        User user = new User();
        user.setTenantId(tenantId);
        user.setFullName(email);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole("OWNER");
        user.setStatus(status);
        return userRepository.save(user);
    }
}
