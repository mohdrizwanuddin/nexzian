
package com.nexzian.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nexzian.dto.PublicPropertyResponse;
import com.nexzian.dto.PropertyResponse;
import com.nexzian.entity.Tenant;
import com.nexzian.service.PropertyService;
import com.nexzian.service.TenantService;

@RestController
@RequestMapping("/api/v1/public")
public class PublicPropertyController {

    private final TenantService tenantService;
    private final PropertyService propertyService;

    public PublicPropertyController(
            TenantService tenantService,
            PropertyService propertyService) {

        this.tenantService = tenantService;
        this.propertyService = propertyService;
    }

    // Get all published properties for an agency
    @GetMapping("/{websiteSlug}/properties")
    public ResponseEntity<List<PublicPropertyResponse>> getPublicProperties(
            @PathVariable("websiteSlug") String websiteSlug) {

        return tenantService.getActiveTenantByWebsiteSlug(websiteSlug)
                .map(tenant -> {
                    List<PublicPropertyResponse> properties =
                            propertyService.getPublicProperties(tenant.getId())
                                    .stream()
                                    .map(PublicPropertyResponse::new)
                                    .toList();

                    return ResponseEntity.ok(properties);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // Get one published property for an agency
    @GetMapping("/{websiteSlug}/properties/{propertyId}")
    public ResponseEntity<PublicPropertyResponse> getPublicPropertyById(
            @PathVariable("websiteSlug") String websiteSlug,
            @PathVariable("propertyId") UUID propertyId) {

        return tenantService.getActiveTenantByWebsiteSlug(websiteSlug)
                .flatMap(tenant ->
                        propertyService.getPublicPropertyById(
                                propertyId, tenant.getId()))
                .map(PublicPropertyResponse::new)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
