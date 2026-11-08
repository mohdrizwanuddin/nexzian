
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

import com.nexzian.dto.LeadRequest;
import com.nexzian.dto.LeadResponse;
import com.nexzian.entity.User;
import com.nexzian.repository.UserRepository;
import com.nexzian.service.LeadService;

@RestController
@RequestMapping("/api/v1/leads")
@CrossOrigin(origins = "http://localhost:5173")
public class LeadController {

    private final LeadService leadService;
    private final UserRepository userRepository;

    public LeadController(
            LeadService leadService,
            UserRepository userRepository) {
        this.leadService = leadService;
        this.userRepository = userRepository;
    }

    private UUID getAuthenticatedTenantId(UserDetails principal) {

        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found"));

        return user.getTenantId();
    }

    @PostMapping
    public ResponseEntity<LeadResponse> createLead(
            @RequestBody @Valid LeadRequest request,
            @AuthenticationPrincipal UserDetails principal) {

        UUID tenantId = getAuthenticatedTenantId(principal);

        LeadResponse savedLead =
                leadService.createLead(request, tenantId);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(savedLead);
    }

    @GetMapping
    public ResponseEntity<List<LeadResponse>> getAllLeads(
            @AuthenticationPrincipal UserDetails principal) {

        UUID tenantId = getAuthenticatedTenantId(principal);

        return ResponseEntity.ok(
                leadService.getAllLeads(tenantId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<LeadResponse> getLeadById(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal UserDetails principal) {

        UUID tenantId = getAuthenticatedTenantId(principal);

        return leadService.getLeadById(id, tenantId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<LeadResponse> updateLead(
            @PathVariable("id") UUID id,
            @RequestBody @Valid LeadRequest request,
            @AuthenticationPrincipal UserDetails principal) {

        UUID tenantId = getAuthenticatedTenantId(principal);

        return leadService.updateLead(id, tenantId, request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteLead(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal UserDetails principal) {

        UUID tenantId = getAuthenticatedTenantId(principal);

        boolean deleted = leadService.deleteLead(id, tenantId);

        if (!deleted) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.noContent().build();
    }
}
