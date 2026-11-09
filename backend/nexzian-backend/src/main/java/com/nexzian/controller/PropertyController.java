
package com.nexzian.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.nexzian.dto.PropertyRequest;
import com.nexzian.dto.PropertyResponse;
import com.nexzian.entity.User;
import com.nexzian.repository.UserRepository;
import com.nexzian.service.PropertyService;

@RestController
@RequestMapping("/api/v1/properties")
@CrossOrigin(origins = "http://localhost:5173")
public class PropertyController {

    private final PropertyService propertyService;
    private final UserRepository userRepository;

    public PropertyController(
            PropertyService propertyService,
            UserRepository userRepository) {
        this.propertyService = propertyService;
        this.userRepository = userRepository;
    }

    // The caller is always resolved from the authenticated principal (never from the request),
    // so tenant and user id cannot be spoofed by a client.
    private User getAuthenticatedUser(UserDetails principal) {

        return userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Authenticated user not found"));
    }

    private UUID getAuthenticatedTenantId(UserDetails principal) {

        return getAuthenticatedUser(principal).getTenantId();
    }

    // POST - Create property
    @PostMapping
    public ResponseEntity<PropertyResponse> createProperty(
            @RequestBody @Valid PropertyRequest request,
            @AuthenticationPrincipal UserDetails principal) {

        UUID tenantId = getAuthenticatedTenantId(principal);

        PropertyResponse savedProperty =
                propertyService.saveProperty(request, tenantId);

        return ResponseEntity.status(HttpStatus.CREATED).body(savedProperty);
    }

    // GET - List all properties for authenticated tenant
    @GetMapping
    public ResponseEntity<List<PropertyResponse>> getAllProperties(
            @AuthenticationPrincipal UserDetails principal) {

        UUID tenantId = getAuthenticatedTenantId(principal);

        List<PropertyResponse> properties =
                propertyService.getAllProperties(tenantId);

        return ResponseEntity.ok(properties);
    }

    // GET - List the tenant's ARCHIVED properties (owner-only, see SecurityConfig). Declared as a
    // fixed path next to /{id}; Spring always prefers the literal match, and "archived" is not
    // a valid UUID anyway.
    @GetMapping("/archived")
    public ResponseEntity<List<PropertyResponse>> getArchivedProperties(
            @AuthenticationPrincipal UserDetails principal) {

        UUID tenantId = getAuthenticatedTenantId(principal);

        return ResponseEntity.ok(propertyService.getArchivedProperties(tenantId));
    }

    // POST - Restore an archived property (owner-only). Returns the restored property.
    // 404 when it does not exist in the caller's tenant.
    @PostMapping("/{id}/restore")
    public ResponseEntity<PropertyResponse> restoreProperty(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal UserDetails principal) {

        UUID tenantId = getAuthenticatedTenantId(principal);

        return propertyService.restoreProperty(id, tenantId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // GET - Get property by ID
    @GetMapping("/{id}")
    public ResponseEntity<PropertyResponse> getPropertyById(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal UserDetails principal) {

        UUID tenantId = getAuthenticatedTenantId(principal);

        return propertyService.getPropertyById(id, tenantId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // PUT - Update property
    @PutMapping("/{id}")
    public ResponseEntity<PropertyResponse> updateProperty(
            @PathVariable("id") UUID id,
            @RequestBody @Valid PropertyRequest request,
            @AuthenticationPrincipal UserDetails principal) {

        UUID tenantId = getAuthenticatedTenantId(principal);

        return propertyService.updateProperty(id, tenantId, request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // DELETE - Archive (soft delete) a property. The URL and the 204 response are unchanged for
    // clients, but nothing is physically removed: images, image files and leads are kept and
    // the owner can restore the property later. The archiving user's id is recorded for audit.
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProperty(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal UserDetails principal) {

        User user = getAuthenticatedUser(principal);

        boolean deleted = propertyService.archiveProperty(
                id, user.getTenantId(), user.getId());

        if (!deleted) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.noContent().build();
    }
}
