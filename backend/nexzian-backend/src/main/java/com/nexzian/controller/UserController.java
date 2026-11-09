
package com.nexzian.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import com.nexzian.dto.UserResponse;
import com.nexzian.dto.TeamMemberRequest;
import com.nexzian.dto.TeamMemberAccessRequest;
import com.nexzian.entity.User;
import com.nexzian.repository.UserRepository;
import com.nexzian.security.TeamMemberCreationRateLimiter;
import com.nexzian.service.UserService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final UserRepository userRepository;

    private final TeamMemberCreationRateLimiter teamMemberCreationRateLimiter;

    public UserController(
            UserService userService,
            UserRepository userRepository,
            TeamMemberCreationRateLimiter teamMemberCreationRateLimiter) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.teamMemberCreationRateLimiter = teamMemberCreationRateLimiter;
    }

    private UUID getAuthenticatedTenantId(UserDetails principal) {
        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Authenticated user not found"));

        return user.getTenantId();
    }

    // Get all users belonging to the authenticated tenant
    @GetMapping
    public ResponseEntity<List<UserResponse>> getUsersByTenant(
            @AuthenticationPrincipal UserDetails principal) {

        return ResponseEntity.ok(
                userService.getUsersByTenant(getAuthenticatedTenantId(principal)));
    }

    // Get a specific user belonging to the authenticated tenant
    @GetMapping("/{userId}")
    public ResponseEntity<UserResponse> getUserById(
            @PathVariable UUID userId,
            @AuthenticationPrincipal UserDetails principal) {

        return ResponseEntity.ok(
                userService.getUserById(
                        userId, getAuthenticatedTenantId(principal)));
    }

    @PostMapping("/team")
    public ResponseEntity<UserResponse> createTeamMember(
            @Valid @RequestBody TeamMemberRequest request,
            @AuthenticationPrincipal UserDetails principal) {

        // The tenant comes from the authenticated user's own record, never from the request.
        UUID tenantId = getAuthenticatedTenantId(principal);

        // Rate limit FIRST, before any email lookup, and count successes and failures alike:
        // see TeamMemberCreationRateLimiter for why (account-enumeration protection).
        teamMemberCreationRateLimiter.checkAndRecord(tenantId);

        return ResponseEntity.status(HttpStatus.CREATED).body(
                userService.createTeamMember(tenantId, request));
    }

    @PutMapping("/{userId}/access")
    public ResponseEntity<UserResponse> updateTeamMemberAccess(
            @PathVariable UUID userId,
            @Valid @RequestBody TeamMemberAccessRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(userService.updateTeamMemberAccess(
                getAuthenticatedTenantId(principal), userId, request));
    }
}
