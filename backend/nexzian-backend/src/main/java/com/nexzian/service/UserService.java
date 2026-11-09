
package com.nexzian.service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.nexzian.dto.UserResponse;
import com.nexzian.dto.TeamMemberRequest;
import com.nexzian.dto.TeamMemberAccessRequest;
import com.nexzian.entity.User;
import com.nexzian.exception.EmailUnavailableException;
import com.nexzian.mapper.UserMapper;
import com.nexzian.repository.TenantRepository;
import com.nexzian.repository.UserRepository;
import com.nexzian.util.EmailNormalizer;
import com.nexzian.util.SqlErrors;

@Service
@Transactional
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

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

    /**
     * Adds an AGENT or STAFF member to the caller's agency.
     *
     * {@code tenantId} is the authenticated owner's own tenant (resolved by the controller from
     * the database, never from the request), and the role can only be AGENT or STAFF, so this can
     * neither create a user in another agency nor an OWNER / PLATFORM_ADMIN.
     *
     * Email identity: login finds people by email across ALL agencies, so the address must be
     * unique platform-wide and compared in canonical form (see EmailNormalizer), and it must be a
     * supported address (printable ASCII only; an internal caller that skipped request validation
     * gets UnsupportedEmailException, HTTP 400, before anything is stored). A taken address
     * - in this agency, another agency, or lost to a concurrent request - always produces the SAME
     * generic {@link EmailUnavailableException}; it must not reveal where, or whether in another
     * agency, the account exists.
     */
    public UserResponse createTeamMember(UUID tenantId, TeamMemberRequest request) {
        String email = EmailNormalizer.normalizeForStorage(request.getEmail());

        String role = request.getRole().trim().toUpperCase(Locale.ROOT);
        if (!role.equals("AGENT") && !role.equals("STAFF")) {
            throw new IllegalArgumentException("Team members must have the AGENT or STAFF role");
        }

        // Courtesy pre-check (cheap, avoids a needless BCrypt hash). IgnoreCase also covers any
        // legacy mixed-case row on a database that has not been migrated yet. It is NOT what
        // guarantees uniqueness - the unique constraint below is.
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new EmailUnavailableException();
        }

        User user = new User();
        user.setTenantId(tenantId); user.setFullName(request.getFullName().trim());
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(role); user.setStatus("ACTIVE");

        try {
            // Flush now so a unique violation surfaces here, inside this method, where it can be
            // translated; otherwise it would only appear at commit time as an HTTP 500.
            return UserMapper.toResponse(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException exception) {
            if (SqlErrors.isUniqueViolation(exception)) {
                // Two concurrent requests (even with differently-cased versions of the address,
                // which normalize to the same value) both passed the pre-check; the database let
                // exactly one in. The loser gets the same generic answer as any duplicate.
                // Deliberately not logging the address.
                log.warn("Team member creation lost a uniqueness race for tenant {}", tenantId);
                throw new EmailUnavailableException();
            }
            throw exception;
        }
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
