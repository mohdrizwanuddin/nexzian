
package com.nexzian.service;

import java.util.List;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.nexzian.dto.UserResponse;
import com.nexzian.dto.TeamMemberRequest;
import com.nexzian.dto.TeamMemberAccessRequest;
import com.nexzian.entity.User;
import com.nexzian.mapper.UserMapper;
import com.nexzian.repository.TenantRepository;
import com.nexzian.repository.UserRepository;

@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(
            UserRepository userRepository,
            TenantRepository tenantRepository,
            PasswordEncoder passwordEncoder) {

        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // Get all users belonging to a tenant
    @Transactional(readOnly = true)
    public List<UserResponse> getUsersByTenant(UUID tenantId) {

        return userRepository.findAllByTenantId(tenantId)
                .stream()
                .map(UserMapper::toResponse)
                .toList();
    }

    // Get a specific user belonging to a tenant
    @Transactional(readOnly = true)
    public UserResponse getUserById(UUID userId, UUID tenantId) {

        return userRepository.findByIdAndTenantId(userId, tenantId)
                .map(UserMapper::toResponse)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "User not found"));
    }

    public UserResponse createTeamMember(UUID tenantId, TeamMemberRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("A user with this email already exists");
        }
        String role = request.getRole().trim().toUpperCase();
        if (!role.equals("AGENT") && !role.equals("STAFF")) {
            throw new IllegalArgumentException("Team members must have the AGENT or STAFF role");
        }
        User user = new User();
        user.setTenantId(tenantId); user.setFullName(request.getFullName().trim());
        user.setEmail(request.getEmail().trim().toLowerCase());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(role); user.setStatus("ACTIVE");
        return UserMapper.toResponse(userRepository.save(user));
    }

    public UserResponse updateTeamMemberAccess(UUID tenantId, UUID userId, TeamMemberAccessRequest request) {
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team member not found"));
        if ("OWNER".equals(user.getRole())) {
            throw new IllegalArgumentException("The agency owner access cannot be changed here");
        }
        String role = request.getRole().trim().toUpperCase();
        String status = request.getStatus().trim().toUpperCase();
        if ((!role.equals("AGENT") && !role.equals("STAFF")) || (!status.equals("ACTIVE") && !status.equals("INACTIVE"))) {
            throw new IllegalArgumentException("Invalid team member role or status");
        }
        user.setRole(role); user.setStatus(status);
        return UserMapper.toResponse(userRepository.save(user));
    }
}
