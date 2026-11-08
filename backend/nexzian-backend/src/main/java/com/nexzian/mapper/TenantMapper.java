
package com.nexzian.mapper;

import com.nexzian.dto.TenantRequest;
import com.nexzian.dto.TenantResponse;
import com.nexzian.entity.Tenant;

public class TenantMapper {

    public static Tenant toEntity(TenantRequest request) {

        Tenant tenant = new Tenant();

        tenant.setBusinessName(request.getBusinessName());
        tenant.setEmail(request.getEmail());
        tenant.setPhone(request.getPhone());
        tenant.setCity(request.getCity());
        tenant.setLocality(request.getLocality());

        return tenant;
    }

    public static TenantResponse toResponse(Tenant tenant) {

        TenantResponse response = new TenantResponse();

        response.setId(tenant.getId());
        response.setBusinessName(tenant.getBusinessName());
        response.setWebsiteSlug(tenant.getWebsiteSlug());
        response.setEmail(tenant.getEmail());
        response.setPhone(tenant.getPhone());
        response.setCity(tenant.getCity());
        response.setLocality(tenant.getLocality());
        response.setSubscriptionPlan(tenant.getSubscriptionPlan());
        response.setStatus(tenant.getStatus());
        response.setCreatedAt(tenant.getCreatedAt());
        response.setUpdatedAt(tenant.getUpdatedAt());

        return response;
    }
}
