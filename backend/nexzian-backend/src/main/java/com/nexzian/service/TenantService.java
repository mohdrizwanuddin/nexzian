
package com.nexzian.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nexzian.dto.TenantRequest;
import com.nexzian.dto.TenantResponse;
import com.nexzian.entity.Tenant;
import com.nexzian.mapper.TenantMapper;
import com.nexzian.repository.TenantRepository;
import com.nexzian.util.EmailNormalizer;

@Service
@Transactional
public class TenantService {

    private final TenantRepository tenantRepository;

    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    public TenantResponse registerTenant(TenantRequest request) {

        // Compare the canonical form (the Tenant entity stores the same canonical value).
        // IgnoreCase additionally covers a legacy mixed-case row before the R4 migration.
        if (tenantRepository.existsByEmailIgnoreCase(EmailNormalizer.normalizeForStorage(request.getEmail()))) {
            throw new IllegalArgumentException(
                    "A tenant with this email already exists");
        }

        if (tenantRepository.existsByBusinessNameIgnoreCase(request.getBusinessName())) {
            throw new IllegalArgumentException(
                    "An agency with this business name already exists");
        }

        Tenant tenant = TenantMapper.toEntity(request);
        tenant.setWebsiteSlug(createAvailableWebsiteSlug(request.getBusinessName()));

        Tenant savedTenant = tenantRepository.save(tenant);

        return TenantMapper.toResponse(savedTenant);
    }

    @Transactional(readOnly = true)
    public List<TenantResponse> getAllTenants() {

        return tenantRepository.findAll()
                .stream()
                .map(TenantMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<TenantResponse> getTenantById(UUID tenantId) {

        return tenantRepository.findById(tenantId)
                .map(TenantMapper::toResponse);
    }

    private String createAvailableWebsiteSlug(String businessName) {
        String baseSlug = businessName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (baseSlug.isBlank()) baseSlug = "agency";

        String slug = baseSlug;
        int suffix = 2;
        while (tenantRepository.existsByWebsiteSlug(slug)) {
            slug = baseSlug + "-" + suffix++;
        }
        return slug;
    }

    // Public website: find an active agency using its website slug
    @Transactional(readOnly = true)
    public Optional<Tenant> getActiveTenantByWebsiteSlug(
            String websiteSlug) {

        return tenantRepository.findByWebsiteSlug(websiteSlug)
                .filter(tenant -> "ACTIVE".equalsIgnoreCase(
                        tenant.getStatus()));
    }
}
