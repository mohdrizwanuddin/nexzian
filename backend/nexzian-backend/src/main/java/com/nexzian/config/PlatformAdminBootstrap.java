package com.nexzian.config;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.nexzian.entity.User;
import com.nexzian.repository.UserRepository;

@Configuration
public class PlatformAdminBootstrap {
    @Bean
    ApplicationRunner createPlatformAdmin(UserRepository userRepository, PasswordEncoder passwordEncoder,
            @Value("${app.platform-admin.email:}") String email,
            @Value("${app.platform-admin.password:}") String password) {
        return arguments -> {
            if (email.isBlank() || password.isBlank() || userRepository.existsByEmail(email)) return;
            User admin = new User();
            admin.setTenantId(new UUID(0L, 0L));
            admin.setFullName("Nexzian Platform Admin");
            admin.setEmail(email.trim().toLowerCase());
            admin.setPassword(passwordEncoder.encode(password));
            admin.setRole("PLATFORM_ADMIN");
            admin.setStatus("ACTIVE");
            userRepository.save(admin);
        };
    }
}
