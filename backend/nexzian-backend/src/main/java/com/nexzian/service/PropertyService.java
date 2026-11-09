
package com.nexzian.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nexzian.dto.PropertyRequest;
import com.nexzian.dto.PropertyResponse;
import com.nexzian.entity.Property;
import com.nexzian.exception.PropertyArchivedException;
import com.nexzian.mapper.PropertyMapper;
import com.nexzian.repository.PropertyRepository;

/**
 * Property business rules, including the archive (soft delete) lifecycle.
 *
 * Lifecycle: active -> archived (DELETE) -> active again (restore). Archiving never removes a
 * property row, its image rows, its image files or the leads that reference it, so agency
 * history is preserved. Every method here is scoped by tenantId: a property of another agency
 * is indistinguishable from a missing one (404 at the controller).
 */
@Service
@Transactional
public class PropertyService {

    private final PropertyRepository propertyRepository;

    public PropertyService(PropertyRepository propertyRepository) {
        this.propertyRepository = propertyRepository;
    }

    // Create a new property
    public PropertyResponse saveProperty(PropertyRequest request, UUID tenantId) {

        Property property = PropertyMapper.toEntity(request);

        property.setTenantId(tenantId);

        Property savedProperty = propertyRepository.save(property);

        return PropertyMapper.toResponse(savedProperty);
    }

    // Get all ACTIVE properties belonging to a tenant (archived ones are listed separately)
    @Transactional(readOnly = true)
    public List<PropertyResponse> getAllProperties(UUID tenantId) {

        return propertyRepository.findAllByTenantIdAndDeletedAtIsNull(tenantId)
                .stream()
                .map(PropertyMapper::toResponse)
                .toList();
    }

    // Get the tenant's archived properties (owner "Archived properties" view)
    @Transactional(readOnly = true)
    public List<PropertyResponse> getArchivedProperties(UUID tenantId) {

        return propertyRepository.findAllByTenantIdAndDeletedAtIsNotNull(tenantId)
                .stream()
                .map(PropertyMapper::toResponse)
                .toList();
    }

    // Get one property belonging to a tenant. Archived properties are still readable by their
    // owner (the response carries deletedAt) so the Archived view and direct links keep working;
    // they are only hidden from the PUBLIC site and frozen against changes.
    @Transactional(readOnly = true)
    public Optional<PropertyResponse> getPropertyById(
            UUID propertyId, UUID tenantId) {

        return propertyRepository.findByIdAndTenantId(propertyId, tenantId)
                .map(PropertyMapper::toResponse);
    }

    // Update an existing property. Archived properties are frozen: besides keeping them
    // unchanged, this is what stops an archived property being published (publishing is just a
    // status change made through this method).
    //
    // Concurrency rule: an edit must never silently succeed against a property that has become
    // archived. The row is read WITH A WRITE LOCK, so this transaction and any archive / restore
    // of the same property run one after the other. If an archive got there first, this read
    // waits for it, sees the latest (archived) state and fails with 409 below; if this edit got
    // the lock first, the archive waits and then archives the updated property. @DynamicUpdate on
    // Property remains as a second line of defence: even without the lock an edit could never
    // write stale deleted_at / deleted_by values back.
    public Optional<PropertyResponse> updateProperty(
            UUID propertyId, UUID tenantId, PropertyRequest request) {

        return propertyRepository.findByIdAndTenantIdForUpdate(propertyId, tenantId)
                .map(existingProperty -> {

                    if (existingProperty.isArchived()) {
                        throw new PropertyArchivedException();
                    }

                    existingProperty.setTitle(request.getTitle());
                    existingProperty.setDescription(request.getDescription());
                    existingProperty.setTransactionType(request.getTransactionType());
                    existingProperty.setPropertyCategory(request.getPropertyCategory());
                    existingProperty.setPropertyType(request.getPropertyType());
                    existingProperty.setCity(request.getCity());
                    existingProperty.setLocality(request.getLocality());
                    existingProperty.setPrice(request.getPrice());
                    existingProperty.setAreaSqft(request.getAreaSqft());
                    existingProperty.setBedrooms(request.getBedrooms());
                    existingProperty.setBathrooms(request.getBathrooms());
                    existingProperty.setStatus(request.getStatus());

                    existingProperty.setUpdatedAt(LocalDateTime.now());

                    Property updatedProperty =
                            propertyRepository.save(existingProperty);

                    return PropertyMapper.toResponse(updatedProperty);
                });
    }

    /**
     * Archives (soft-deletes) an active property of the tenant.
     *
     * A single atomic UPDATE sets deleted_at, deleted_by and updated_at together (updated_at gets
     * the same timestamp as deleted_at). Nothing else on the property row changes - its status
     * in particular is kept - and images (rows and files) and leads are deliberately untouched
     * so nothing is lost and the property can be restored. Returns false
     * when no ACTIVE property with this id exists in the tenant - that covers a missing id, a
     * foreign tenant's id and an already archived property (re-archiving must not overwrite
     * the original deletedAt / deletedBy audit values).
     */
    public boolean archiveProperty(UUID propertyId, UUID tenantId, UUID archivedByUserId) {

        // One atomic conditional UPDATE (see PropertyRepository.archiveIfActive): the affected-row
        // count is the truth about whether THIS call performed the transition. A read-then-save
        // here would let two concurrent archives both succeed and the later one overwrite the
        // original deletedAt / deletedBy audit values.
        int archived = propertyRepository.archiveIfActive(
                propertyId, tenantId, archivedByUserId, LocalDateTime.now());

        return archived == 1;
    }

    /**
     * Restores an archived property of the tenant.
     *
     * Clears deletedAt / deletedBy and nothing else: status, images and everything else are
     * preserved. It does NOT publish the property - public visibility stays governed by the
     * existing rule (status must be PUBLISHED), so a restore can never expose a listing the
     * owner did not explicitly publish. Restoring a property that is already active is a
     * harmless no-op that returns it. Empty means "not found in this tenant".
     */
    public Optional<PropertyResponse> restoreProperty(UUID propertyId, UUID tenantId) {

        // Atomic transition first; its row count is deliberately ignored: 0 rows means "not
        // archived (already active, or concurrently restored)" or "not in this tenant", and the
        // lookup below tells those apart. Restoring an already-active property stays a harmless
        // success, and concurrent restores all succeed with the same end state.
        propertyRepository.restoreIfArchived(propertyId, tenantId, LocalDateTime.now());

        // Re-read AFTER the bulk update (it cleared the persistence context) so the response
        // reflects the database, never a stale managed entity. Tenant-scoped: a missing or
        // foreign id yields empty -> 404.
        return propertyRepository.findByIdAndTenantId(propertyId, tenantId)
                .map(PropertyMapper::toResponse);
    }

    // PUBLIC WEBSITE: Get only published, non-archived properties of an agency
    @Transactional(readOnly = true)
    public List<PropertyResponse> getPublicProperties(UUID tenantId) {

        return propertyRepository
                .findAllByTenantIdAndStatusAndDeletedAtIsNull(tenantId, "PUBLISHED")
                .stream()
                .map(PropertyMapper::toResponse)
                .toList();
    }

    // PUBLIC WEBSITE: Get one published, non-archived property of an agency
    @Transactional(readOnly = true)
    public Optional<PropertyResponse> getPublicPropertyById(
            UUID propertyId, UUID tenantId) {

        return propertyRepository
                .findByIdAndTenantIdAndStatusAndDeletedAtIsNull(
                        propertyId, tenantId, "PUBLISHED")
                .map(PropertyMapper::toResponse);
    }
}
