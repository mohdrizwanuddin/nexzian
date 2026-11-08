
package com.nexzian.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.nexzian.entity.PropertyImage;

public interface PropertyImageRepository extends JpaRepository<PropertyImage, UUID> {

    List<PropertyImage> findAllByPropertyIdAndTenantIdOrderByCreatedAtAsc(
            UUID propertyId, UUID tenantId);

    Optional<PropertyImage> findByIdAndPropertyIdAndTenantId(
            UUID id, UUID propertyId, UUID tenantId);

    long countByPropertyIdAndTenantId(UUID propertyId, UUID tenantId);

    Optional<PropertyImage> findByPropertyIdAndTenantIdAndFeaturedTrue(
            UUID propertyId, UUID tenantId);
}
