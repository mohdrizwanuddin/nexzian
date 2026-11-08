
package com.nexzian.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.nexzian.entity.Tenant;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByBusinessNameIgnoreCase(String businessName);

    Optional<Tenant> findByWebsiteSlug(String websiteSlug);

    boolean existsByWebsiteSlug(String websiteSlug);
}
