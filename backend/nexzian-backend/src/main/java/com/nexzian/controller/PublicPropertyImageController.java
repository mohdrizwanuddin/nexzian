
package com.nexzian.controller;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nexzian.dto.PublicPropertyImageResponse;
import com.nexzian.entity.Tenant;
import com.nexzian.service.PropertyImageService;
import com.nexzian.service.TenantService;

@RestController
@RequestMapping("/api/v1/public/{websiteSlug}/properties/{propertyId}/images")
public class PublicPropertyImageController {

    private final TenantService tenantService;
    private final PropertyImageService propertyImageService;

    public PublicPropertyImageController(
            TenantService tenantService,
            PropertyImageService propertyImageService) {
        this.tenantService = tenantService;
        this.propertyImageService = propertyImageService;
    }

    // Get all images for a published property
    @GetMapping
    public ResponseEntity<List<PublicPropertyImageResponse>> getPublicPropertyImages(
            @PathVariable("websiteSlug") String websiteSlug,
            @PathVariable("propertyId") UUID propertyId) {

        Optional<Tenant> tenantOptional =
                tenantService.getActiveTenantByWebsiteSlug(websiteSlug);

        if (tenantOptional.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Tenant tenant = tenantOptional.get();

        try {
            List<PublicPropertyImageResponse> images =
                    propertyImageService.getPublicPropertyImages(
                            propertyId, tenant.getId(), websiteSlug);

            return ResponseEntity.ok(images);

        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    // Display an individual image
    @GetMapping("/{imageId}/file")
    public ResponseEntity<Resource> getPublicImageFile(
            @PathVariable("websiteSlug") String websiteSlug,
            @PathVariable("propertyId") UUID propertyId,
            @PathVariable("imageId") UUID imageId) {

        Optional<Tenant> tenantOptional =
                tenantService.getActiveTenantByWebsiteSlug(websiteSlug);

        if (tenantOptional.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Tenant tenant = tenantOptional.get();

        try {
            Resource resource =
                    propertyImageService.getPublicImageFile(
                            propertyId, imageId, tenant.getId());

            MediaType contentType = MediaTypeFactory
                    .getMediaType(resource)
                    .orElse(MediaType.APPLICATION_OCTET_STREAM);

            return ResponseEntity.ok()
                    .contentType(contentType)
                    .body(resource);

        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
    }
}
