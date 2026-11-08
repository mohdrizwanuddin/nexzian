package com.nexzian.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nexzian.dto.AdminAgencyResponse;
import com.nexzian.entity.User;
import com.nexzian.repository.LeadRepository;
import com.nexzian.repository.PropertyRepository;
import com.nexzian.repository.TenantRepository;
import com.nexzian.repository.UserRepository;

@Service
@Transactional(readOnly = true)
public class PlatformAdminService {
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PropertyRepository propertyRepository;
    private final LeadRepository leadRepository;

    public PlatformAdminService(TenantRepository tenantRepository, UserRepository userRepository,
            PropertyRepository propertyRepository, LeadRepository leadRepository) {
        this.tenantRepository = tenantRepository; this.userRepository = userRepository;
        this.propertyRepository = propertyRepository; this.leadRepository = leadRepository;
    }

    public List<AdminAgencyResponse> getAgencies() {
        return tenantRepository.findAll().stream().map(tenant -> {
            User owner = userRepository.findFirstByTenantIdAndRole(tenant.getId(), "OWNER").orElse(null);
            return new AdminAgencyResponse(tenant.getId(), tenant.getBusinessName(), tenant.getWebsiteSlug(),
                    tenant.getStatus(), tenant.getSubscriptionPlan(), tenant.getCity(),
                    owner == null ? null : owner.getFullName(), owner == null ? null : owner.getEmail(),
                    propertyRepository.countByTenantId(tenant.getId()),
                    propertyRepository.findAllByTenantIdAndStatus(tenant.getId(), "PUBLISHED").size(),
                    leadRepository.countByTenantId(tenant.getId()), userRepository.countByTenantId(tenant.getId()),
                    tenant.getCreatedAt());
        }).toList();
    }
}
