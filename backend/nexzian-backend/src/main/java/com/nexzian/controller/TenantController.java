
package com.nexzian.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nexzian.dto.TenantRequest;
import com.nexzian.dto.TenantResponse;
import com.nexzian.dto.PublicTenantResponse;
import com.nexzian.entity.Tenant;
import com.nexzian.entity.User;
import com.nexzian.repository.UserRepository;
import com.nexzian.service.TenantService;

@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantService tenantService;
    private final UserRepository userRepository;

    public TenantController(
            TenantService tenantService,
            UserRepository userRepository) {
        this.tenantService = tenantService;
        this.userRepository = userRepository;
    }

    @PostMapping
    public ResponseEntity<TenantResponse> registerTenant(
            @RequestBody @Valid TenantRequest request) {

        TenantResponse response = tenantService.registerTenant(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<TenantResponse>> getAllTenants() {

        List<TenantResponse> tenants = tenantService.getAllTenants();

        return ResponseEntity.ok(tenants);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TenantResponse> getTenantById(
            @PathVariable("id") UUID id,
            Authentication authentication) {

        // Role and tenant come from the database user, never from the request.
        User caller = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Authenticated user not found"));

        boolean platformAdmin = "PLATFORM_ADMIN".equals(caller.getRole());

        // Other agencies' records are reported as not found, not forbidden.
        if (!platformAdmin && !id.equals(caller.getTenantId())) {
            return ResponseEntity.notFound().build();
        }

        return tenantService.getTenantById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // Public API: Get agency details using website slug
    @GetMapping("/public/{websiteSlug}")
    public ResponseEntity<PublicTenantResponse> getPublicTenant(
            @PathVariable("websiteSlug") String websiteSlug) {

        return tenantService.getActiveTenantByWebsiteSlug(websiteSlug)
                .map(this::toPublicTenantResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private PublicTenantResponse toPublicTenantResponse(Tenant tenant) {

        return new PublicTenantResponse(
                tenant.getBusinessName(),
                tenant.getWebsiteSlug(),
                tenant.getPhone(),
                tenant.getCity(),
                tenant.getLocality()
        );
    }
}
