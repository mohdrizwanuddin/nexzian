
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

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.nexzian.security.JwtAuthenticationFilter;

/**
 * Web security for the whole API: CORS, CSRF, stateless JWT authentication and, above all, the
 * URL authorization policy.
 *
 * The authorization policy is DENY-BY-DEFAULT: every route that is meant to be reachable has
 * its own rule naming its exact HTTP method and path, and the very last rule denies everything
 * else. A controller endpoint added later without a rule is therefore unreachable (401 for
 * anonymous callers, 403 for logged-in ones) until someone consciously decides who may call it.
 * The previous fallback was "any authenticated user", which would have silently exposed such an
 * endpoint to every role, AGENT and STAFF included.
 */
@Configuration
public class SecurityConfig {

    /**
     * Exact body messages written by this class's own 401/403 handlers. They are public so tests
     * can tell a denial produced by Spring Security (route not allowed for the caller) from a
     * 401/403 produced by a controller or service (e.g. a refresh without cookie, a suspended
     * agency), which have different bodies and mean something different.
     */
    public static final String UNAUTHORIZED_MESSAGE = "Authentication is required.";
    public static final String FORBIDDEN_MESSAGE = "You do not have permission to perform this action.";

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
            // CORS runs before authorization and answers browser preflight requests itself.
            .cors(Customizer.withDefaults())
            // CSRF: cookie-based double submit. Authentication travels in HttpOnly cookies, so every
            // state-changing request must also carry the matching X-XSRF-TOKEN header.
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            // No server-side session: each request is authenticated from its JWT (JwtAuthenticationFilter).
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // URL authorization. Rules are evaluated top to bottom and the FIRST match wins, so the
            // order below matters (see the notes on /actuator/health and /tenants). Every rule names
            // an exact HTTP method and path on purpose: a prefix wildcard such as "/properties/**"
            // would silently cover any endpoint added under it later, with whatever permission the
            // wildcard happens to give. With exact rules plus the final denyAll(), a new endpoint
            // is unreachable until someone consciously adds a rule for it (and a test entry in
            // RouteAuthorizationCompletenessTests, which fails otherwise).
            .authorizeHttpRequests(auth -> auth

                // ---- 0. Error dispatch ---------------------------------------------------------------
                // When a controller or Spring MVC sends an error (404 from a ResponseStatusException,
                // 400 for unreadable JSON, 415, an unhandled exception's 500...), the servlet
                // container re-dispatches the request to /error to render it, and Spring Security
                // authorizes that second dispatch as well. The JWT filter does not run for it, so the
                // caller would look anonymous and every such error would be replaced by a misleading
                // 401 "Authentication is required" (the frontend shows it as "session expired").
                // Permitting ONLY the ERROR dispatch type keeps the real status; it does not make
                // /error reachable directly - a client's own request to /error is a REQUEST dispatch
                // and falls through to denyAll() below. The error page itself exposes no message or
                // stack trace (spring.web.error.include-message is never).
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()

                // ---- 1. Public: sign-in, sign-up and the public agency website -----------------------
                // These must work without a login or the product cannot be used at all. Each is bound
                // to its exact method: login/logout/refresh/register are POST, the CSRF token and the
                // public website are read-only GETs, so any other method on these paths is denied.
                // Note: "OPTIONS /**" is deliberately NOT permitted. Browser CORS preflights are
                // answered by the CORS filter (configured above) before this authorization step, so a
                // blanket OPTIONS permit is unnecessary and only let unauthenticated probes through.
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/register-agency").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()

                // The public agency website: agency profile, published listings and their images.
                // Listed one by one instead of "/public/**" so that a future GET added under that
                // prefix is NOT public by accident, and so "/tenants/public" (no slug) is no longer
                // matched here and routed to the "/tenants/{id}" handler without a login.
                .requestMatchers(HttpMethod.GET, "/api/v1/tenants/public/{websiteSlug}").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/{websiteSlug}/properties").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/{websiteSlug}/properties/{propertyId}").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/{websiteSlug}/properties/{propertyId}/images").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/{websiteSlug}/properties/{propertyId}/images/{imageId}/file").permitAll()

                // Liveness check for deployments/monitors. Exact path, and it must come BEFORE the
                // "/actuator/health/**" rule below, which would otherwise also match it.
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()

                // ---- 2. Any logged-in role (behaviour preserved from the old fallback) ----------------
                // These were reachable by every authenticated user only because of the old
                // "anyRequest().authenticated()". They are kept as they were, now stated explicitly;
                // restricting them further is a separate decision.
                .requestMatchers(HttpMethod.GET, "/api/v1", "/actuator", "/actuator/health/**").authenticated()

                // ---- 3. Platform administration ---------------------------------------------------------
                // Cross-agency data. No agency user (OWNER, AGENT, STAFF) may see it.
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/agencies").hasRole("PLATFORM_ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/tenants").hasRole("PLATFORM_ADMIN")

                // ---- 4. One tenant record --------------------------------------------------------------
                // Role gate here; TenantController additionally refuses an OWNER asking for a tenant
                // that is not their own (404), so this rule alone is not the isolation guarantee.
                .requestMatchers(HttpMethod.GET, "/api/v1/tenants/{id}").hasAnyRole("OWNER", "PLATFORM_ADMIN")

                // ---- 5. Agency business data: OWNER only -----------------------------------------------
                // AGENT and STAFF can log in but have no business permissions yet, so they are
                // refused here. PLATFORM_ADMIN is refused too: platform administrators manage the
                // platform, not an agency's listings, leads or team. Tenant isolation inside these
                // routes is enforced in the services (every query is scoped to the caller's tenant).
                //   Properties
                .requestMatchers(HttpMethod.GET, "/api/v1/properties").hasRole("OWNER")
                .requestMatchers(HttpMethod.POST, "/api/v1/properties").hasRole("OWNER")
                .requestMatchers(HttpMethod.GET, "/api/v1/properties/{id}").hasRole("OWNER")
                .requestMatchers(HttpMethod.PUT, "/api/v1/properties/{id}").hasRole("OWNER")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/properties/{id}").hasRole("OWNER")
                // Archive lifecycle: DELETE above now ARCHIVES (soft delete). Viewing the archived
                // list and restoring are equally owner-only - they expose / re-activate agency
                // business data - and, like every rule here, exact method + path.
                .requestMatchers(HttpMethod.GET, "/api/v1/properties/archived").hasRole("OWNER")
                .requestMatchers(HttpMethod.POST, "/api/v1/properties/{id}/restore").hasRole("OWNER")
                //   Property images
                .requestMatchers(HttpMethod.GET, "/api/v1/properties/{propertyId}/images").hasRole("OWNER")
                .requestMatchers(HttpMethod.POST, "/api/v1/properties/{propertyId}/images").hasRole("OWNER")
                .requestMatchers(HttpMethod.GET, "/api/v1/properties/{propertyId}/images/{imageId}/file").hasRole("OWNER")
                .requestMatchers(HttpMethod.PUT, "/api/v1/properties/{propertyId}/images/{imageId}/featured").hasRole("OWNER")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/properties/{propertyId}/images/{imageId}").hasRole("OWNER")
                //   Leads
                .requestMatchers(HttpMethod.GET, "/api/v1/leads").hasRole("OWNER")
                .requestMatchers(HttpMethod.POST, "/api/v1/leads").hasRole("OWNER")
                .requestMatchers(HttpMethod.GET, "/api/v1/leads/{id}").hasRole("OWNER")
                .requestMatchers(HttpMethod.PUT, "/api/v1/leads/{id}").hasRole("OWNER")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/leads/{id}").hasRole("OWNER")
                //   Team members
                .requestMatchers(HttpMethod.GET, "/api/v1/users").hasRole("OWNER")
                .requestMatchers(HttpMethod.GET, "/api/v1/users/{userId}").hasRole("OWNER")
                .requestMatchers(HttpMethod.POST, "/api/v1/users/team").hasRole("OWNER")
                .requestMatchers(HttpMethod.PUT, "/api/v1/users/{userId}/access").hasRole("OWNER")

                // ---- 6. Everything else is DENIED ---------------------------------------------------------
                // Unknown paths, methods that were never declared (PATCH, HEAD, a plain OPTIONS...), the
                // legacy endpoints removed earlier, static content, actuator endpoints that are not
                // exposed, and any endpoint a developer adds without a rule. Anonymous callers get 401
                // from the entry point below; logged-in callers get 403. This replaces the old
                // "any authenticated user" fallback, which opened every forgotten route to every role.
                .anyRequest().denyAll()
            )
            .exceptionHandling(errors -> errors
                // Anonymous caller reached a protected route: 401.
                .authenticationEntryPoint((request, response, exception) ->
                    writeSecurityError(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "Unauthorized", UNAUTHORIZED_MESSAGE))
                // Logged-in caller whose role is not allowed on this route: 403.
                .accessDeniedHandler((request, response, exception) ->
                    writeSecurityError(response, HttpServletResponse.SC_FORBIDDEN,
                        "Forbidden", FORBIDDEN_MESSAGE)))
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
