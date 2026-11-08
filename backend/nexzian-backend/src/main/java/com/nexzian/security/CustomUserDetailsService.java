
package com.nexzian.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.nexzian.repository.UserRepository;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final TenantAccessPolicy tenantAccessPolicy;

    public CustomUserDetailsService(
            UserRepository userRepository,
            TenantAccessPolicy tenantAccessPolicy) {
        this.userRepository = userRepository;
        this.tenantAccessPolicy = tenantAccessPolicy;
    }

    @Override
    public UserDetails loadUserByUsername(String email)
            throws UsernameNotFoundException {

        com.nexzian.entity.User user = userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new UsernameNotFoundException(
                                "User not found: " + email));

        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPassword())
                .authorities("ROLE_" + user.getRole())
                // Disabled if the user is inactive OR their agency is not ACTIVE.
                // The JWT filter refuses disabled users, so tokens issued before a
                // suspension stop working immediately.
                .disabled(!"ACTIVE".equals(user.getStatus())
                        || !tenantAccessPolicy.isAllowed(user))
                .build();
    }
}
