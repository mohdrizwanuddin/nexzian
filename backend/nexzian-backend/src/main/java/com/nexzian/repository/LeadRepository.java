
package com.nexzian.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.nexzian.entity.Lead;

@Repository
public interface LeadRepository extends JpaRepository<Lead, UUID> {

    List<Lead> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<Lead> findByIdAndTenantId(UUID id, UUID tenantId);

    long countByTenantId(UUID tenantId);

    long countByTenantIdAndStatus(UUID tenantId, String status);
}
