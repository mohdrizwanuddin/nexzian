
package com.nexzian.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.nexzian.dto.PropertyImageResponse;
import com.nexzian.entity.User;
import com.nexzian.repository.UserRepository;
import com.nexzian.service.PropertyImageService;

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/images")
public class PropertyImageController {

    private final PropertyImageService propertyImageService;
    private final UserRepository userRepository;

    public PropertyImageController(
            PropertyImageService propertyImageService,
            UserRepository userRepository) {

        this.propertyImageService = propertyImageService;
        this.userRepository = userRepository;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PropertyImageResponse> uploadImage(
            @PathVariable("propertyId") UUID propertyId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {

        UUID tenantId = getTenantId(authentication);

        PropertyImageResponse response =
                propertyImageService.uploadImage(
                        propertyId, tenantId, file);

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<PropertyImageResponse>> getPropertyImages(
            @PathVariable("propertyId") UUID propertyId,
            Authentication authentication) {

        UUID tenantId = getTenantId(authentication);

        return ResponseEntity.ok(
                propertyImageService.getPropertyImages(
                        propertyId, tenantId));
    }

    @GetMapping("/{imageId}/file")
    public ResponseEntity<Resource> getImageFile(
            @PathVariable("propertyId") UUID propertyId,
            @PathVariable("imageId") UUID imageId,
            Authentication authentication) {

        UUID tenantId = getTenantId(authentication);

        Resource resource = propertyImageService.getImageFile(
                propertyId, imageId, tenantId);

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"property-image\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @PutMapping("/{imageId}/featured")
    public ResponseEntity<PropertyImageResponse> setFeaturedImage(
            @PathVariable("propertyId") UUID propertyId,
            @PathVariable("imageId") UUID imageId,
            Authentication authentication) {

        UUID tenantId = getTenantId(authentication);

        return ResponseEntity.ok(
                propertyImageService.setFeaturedImage(
                        propertyId, imageId, tenantId));
    }

    @DeleteMapping("/{imageId}")
    public ResponseEntity<String> deleteImage(
            @PathVariable("propertyId") UUID propertyId,
            @PathVariable("imageId") UUID imageId,
            Authentication authentication) {

        UUID tenantId = getTenantId(authentication);

        propertyImageService.deleteImage(
                propertyId, imageId, tenantId);

        return ResponseEntity.ok(
                "Property image deleted successfully");
    }

    private UUID getTenantId(Authentication authentication) {

        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Authenticated user not found"));

        return user.getTenantId();
    }
}
