package com.nexzian.security;

import org.springframework.stereotype.Component;

import com.nexzian.entity.User;
import com.nexzian.repository.TenantRepository;

/**
 * Single place that decides whether a user's agency (tenant) may use the private API.
 *
 * Rules (fail closed):
 *  - PLATFORM_ADMIN is exempt: platform administrators belong to no agency.
 *  - Everyone else is allowed only if their tenant exists AND its status is
 *    ACTIVE (compared case-insensitively, matching the public website rule in
 *    TenantService.getActiveTenantByWebsiteSlug).
 *  - A missing tenant, or any other status (SUSPENDED, INACTIVE, unknown, blank),
 *    is denied.
 *
 * Used by CustomUserDetailsService (so existing JWTs stop working immediately),
 * and by AuthService.login and AuthService.refresh.
 */
@Component
public class TenantAccessPolicy {

    private static final String PLATFORM_ADMIN_ROLE = "PLATFORM_ADMIN";
    private static final String ACTIVE = "ACTIVE";

    private final TenantRepository tenantRepository;

    public TenantAccessPolicy(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    public boolean isAllowed(User user) {

        if (PLATFORM_ADMIN_ROLE.equals(user.getRole())) {
            return true;
        }

        if (user.getTenantId() == null) {
            return false;
        }

        return tenantRepository.findById(user.getTenantId())
                .map(tenant -> ACTIVE.equalsIgnoreCase(tenant.getStatus()))
                .orElse(false);
    }
}
