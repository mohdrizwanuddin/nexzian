
package com.nexzian.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.http.MediaType;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.nexzian.security.JwtAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration configuration = new CorsConfiguration();

        configuration.setAllowedOrigins(allowedOrigins);

        configuration.setAllowedMethods(
                List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));

        configuration.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "X-XSRF-TOKEN"));

        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration("/**", configuration);

        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {

        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth

                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                // Agency + first OWNER registration: the only public sign-up endpoint.
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/register-agency").permitAll()

                .requestMatchers(
                    "/api/v1/auth/login",
                    "/api/v1/auth/logout",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/csrf",
                    "/actuator/health"
                ).permitAll()

                // Public agency details
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/tenants/public/**"
                ).permitAll()

                // Public agency property listings and details
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/public/**"
                ).permitAll()

                // Full tenant list: platform administrators only.
                .requestMatchers(HttpMethod.GET, "/api/v1/tenants")
                    .hasRole("PLATFORM_ADMIN")

                // Single tenant record: role gate here, ownership check in TenantController.
                .requestMatchers(HttpMethod.GET, "/api/v1/tenants/{id}")
                    .hasAnyRole("OWNER", "PLATFORM_ADMIN")

                .requestMatchers(
                    "/api/v1/admin/**"
                ).hasRole("PLATFORM_ADMIN")

                .requestMatchers(
                    "/api/v1/properties/**",
                    "/api/v1/leads/**",
                    "/api/v1/users/**"
                ).hasRole("OWNER")

                .anyRequest().authenticated()
            )
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) ->
                    writeSecurityError(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "Unauthorized", "Authentication is required."))
                .accessDeniedHandler((request, response, exception) ->
                    writeSecurityError(response, HttpServletResponse.SC_FORBIDDEN,
                        "Forbidden", "You do not have permission to perform this action.")))
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .addFilterBefore(
                jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter.class
            );

        return http.build();
    }

    private static void writeSecurityError(
            jakarta.servlet.http.HttpServletResponse response,
            int status,
            String error,
            String message) throws java.io.IOException {

        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":" + status
                + ",\"error\":\"" + error
                + "\",\"message\":\"" + message + "\"}");
    }
}
