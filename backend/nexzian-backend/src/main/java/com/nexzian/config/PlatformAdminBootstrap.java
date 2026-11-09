package com.nexzian.config;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.nexzian.entity.User;
import com.nexzian.repository.UserRepository;
import com.nexzian.util.EmailNormalizer;

@Configuration
public class PlatformAdminBootstrap {

    /**
     * Creates the platform administrator from configuration, once.
     *
     * Idempotent across restarts: the configured address is normalized (EmailNormalizer) and that
     * SAME canonical value is used for both the "already exists?" check and the saved account.
     * Previously the raw configured value was checked while a lowercased copy was saved, so a
     * configuration such as "Admin@Example.com" passed the check on every restart (the stored
     * row is lowercase), tried to insert again and hit the unique constraint, which would
     * abort application start-up. Public so that idempotency can be tested directly.
     */
    @Bean
    public ApplicationRunner createPlatformAdmin(UserRepository userRepository, PasswordEncoder passwordEncoder,
            @Value("${app.platform-admin.email:}") String email,
            @Value("${app.platform-admin.password:}") String password) {
        return arguments -> {
            if (email.isBlank() || password.isBlank()) return;

            // Existence is checked with the plain normalization first, so an administrator who
            // already exists never makes start-up fail, whatever the configured spelling.
            String normalizedEmail = EmailNormalizer.normalize(email);
            if (userRepository.existsByEmail(normalizedEmail)) return;

            User admin = new User();
            admin.setTenantId(new UUID(0L, 0L));
            admin.setFullName("Nexzian Platform Admin");
            // Only when CREATING is the supported-address policy enforced (ASCII, no whitespace):
            // a misconfigured address fails fast at start-up with a clear message instead of
            // being refused later by the database CHECK constraint.
            admin.setEmail(EmailNormalizer.normalizeForStorage(email));
            admin.setPassword(passwordEncoder.encode(password));
            admin.setRole("PLATFORM_ADMIN");
            admin.setStatus("ACTIVE");
            userRepository.save(admin);
        };
    }
}
