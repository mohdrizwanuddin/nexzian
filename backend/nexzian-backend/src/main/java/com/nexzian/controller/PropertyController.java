
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

    private UUID getAuthenticatedTenantId(UserDetails principal) {

        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Authenticated user not found"));

        return user.getTenantId();
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

    // DELETE - Delete property
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProperty(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal UserDetails principal) {

        UUID tenantId = getAuthenticatedTenantId(principal);

        boolean deleted = propertyService.deleteProperty(id, tenantId);

        if (!deleted) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.noContent().build();
    }
}
