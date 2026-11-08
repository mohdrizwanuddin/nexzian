package com.nexzian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nexzian.entity.User;
import com.nexzian.repository.PropertyRepository;
import com.nexzian.repository.TenantRepository;
import com.nexzian.repository.UserRepository;
import com.nexzian.security.RegistrationRateLimiter;

import jakarta.servlet.http.Cookie;

/**
 * Proves the agency + owner creation is atomic: when creating the owner fails,
 * the tenant insert is rolled back. Uses a spy bean, so it runs in its own context
 * with its own in-memory database to avoid interfering with the other test classes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:nexzian-rollback-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
class AgencyRegistrationRollbackTests {

    private static final String JSON = "{\"businessName\":\"Rollback Realty\",\"fullName\":\"Owner\","
            + "\"email\":\"owner@rollback.example\",\"password\":\"Str0ng-password\"}";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private PropertyRepository propertyRepository;
    @Autowired private RegistrationRateLimiter registrationRateLimiter;
    @MockitoSpyBean private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        registrationRateLimiter.clearAll();
        Mockito.reset(userRepository);
        propertyRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();
    }

    @Test
    void tenantIsRolledBackWhenOwnerCreationFails() throws Exception {
        doThrow(new IllegalStateException("simulated owner creation failure"))
                .when(userRepository).saveAndFlush(any(User.class));

        assertThrows(Exception.class, this::registerRollbackRealty);

        assertEquals(0, tenantRepository.count(), "tenant insert must be rolled back");
        assertEquals(0, userRepository.count());

        // Nothing is left over to block a genuine registration with the same details.
        Mockito.reset(userRepository);
        Cookie csrf = csrfCookie();
        mockMvc.perform(post("/api/v1/auth/register-agency")
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON))
                .andExpect(status().isCreated());

        assertEquals(1, tenantRepository.count());
        assertEquals(1, userRepository.count());
    }

    @Test
    void genuineUniqueViolationBecomesConflictAndRollsBackTheTenant() throws Exception {
        // SQLState 23505 = unique_violation, wrapped the way Spring wraps it.
        doThrow(new DataIntegrityViolationException("duplicate key",
                new SQLException("duplicate key value violates unique constraint", "23505")))
                .when(userRepository).saveAndFlush(any(User.class));

        Cookie csrf = csrfCookie();
        mockMvc.perform(post("/api/v1/auth/register-agency")
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON))
                .andExpect(status().isConflict());

        assertEquals(0, tenantRepository.count(), "tenant insert must be rolled back");
    }

    @Test
    void nonUniqueIntegrityViolationIsNotReportedAsADuplicate() throws Exception {
        // SQLState 23502 = not_null_violation: a server-side problem, not a duplicate.
        DataIntegrityViolationException notNull = new DataIntegrityViolationException(
                "null value in column", new SQLException("null value violates not-null", "23502"));
        doThrow(notNull).when(userRepository).saveAndFlush(any(User.class));

        Exception thrown = assertThrows(Exception.class, this::registerRollbackRealty);

        // It must propagate as the integrity violation, not be turned into a 409 duplicate message.
        assertSame(notNull, rootOfType(thrown, DataIntegrityViolationException.class));
        assertEquals(0, tenantRepository.count(), "tenant insert must be rolled back");
    }

    @Test
    void integrityViolationWithoutAnySqlStateIsNotReportedAsADuplicate() throws Exception {
        DataIntegrityViolationException unknown =
                new DataIntegrityViolationException("integrity problem with no SQL cause");
        doThrow(unknown).when(userRepository).saveAndFlush(any(User.class));

        Exception thrown = assertThrows(Exception.class, this::registerRollbackRealty);

        assertSame(unknown, rootOfType(thrown, DataIntegrityViolationException.class));
        assertEquals(0, tenantRepository.count());
    }

    private static Throwable rootOfType(Throwable exception, Class<? extends Throwable> type) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return current;
            }
        }
        return null;
    }

    private void registerRollbackRealty() throws Exception {
        Cookie csrf = csrfCookie();
        mockMvc.perform(post("/api/v1/auth/register-agency")
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON));
    }

    private Cookie csrfCookie() throws Exception {
        return mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
    }
}
