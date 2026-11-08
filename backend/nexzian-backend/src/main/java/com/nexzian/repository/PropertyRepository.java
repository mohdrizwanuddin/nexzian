
package com.nexzian.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.nexzian.entity.Property;

@Repository
public interface PropertyRepository extends JpaRepository<Property, UUID> {

    List<Property> findAllByTenantId(UUID tenantId);

    Optional<Property> findByIdAndTenantId(UUID id, UUID tenantId);

    long countByTenantId(UUID tenantId);

    // Public website: fetch only published properties of an agency
    List<Property> findAllByTenantIdAndStatus(
            UUID tenantId,
            String status
    );

    // Public website: fetch one published property belonging to an agency
    Optional<Property> findByIdAndTenantIdAndStatus(
            UUID id,
            UUID tenantId,
            String status
    );
}
