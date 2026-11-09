
package com.nexzian.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.nexzian.entity.Property;

@Repository
public interface PropertyRepository extends JpaRepository<Property, UUID> {

    // Archive-UNAWARE finders: they return archived rows too. Production code uses them only
    // where seeing archived rows is intended (see the note below); tests use the two ALL-state
    // ones to prove archiving removes nothing.
    List<Property> findAllByTenantId(UUID tenantId);

    Optional<Property> findByIdAndTenantId(UUID id, UUID tenantId);

    long countByTenantId(UUID tenantId);

    // ---- Archive-aware queries ------------------------------------------------------------
    // "Active" = deleted_at IS NULL. Every LIST, COUNT and PUBLIC path must use these (not the
    // unfiltered finders above) so an archived property can never leak into a public page or a
    // dashboard count.
    // The unfiltered findByIdAndTenantId is used on purpose where an archived row must be SEEN:
    //  * owner read-only views and restore;
    //  * resolving the title of a lead's historical property;
    //  * the edit / image-mutation paths, which load the row and then refuse it with 409 when
    //    it is archived (a filtered finder would answer 404, hiding WHY the change is refused).
    // The edit path uses the locking variant findByIdAndTenantIdForUpdate below.

    List<Property> findAllByTenantIdAndDeletedAtIsNull(UUID tenantId);

    List<Property> findAllByTenantIdAndDeletedAtIsNotNull(UUID tenantId);

    Optional<Property> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    long countByTenantIdAndDeletedAtIsNull(UUID tenantId);

    // ---- Atomic archive / restore transitions -----------------------------------------------
    // Each transition is ONE conditional UPDATE, so the database - not a read-then-save in Java -
    // decides who performed it. Consequences:
    //  * two concurrent archives: the second one's "deleted_at IS NULL" no longer matches once
    //    the first commits, so it updates 0 rows and cannot overwrite the original deletedAt /
    //    deletedBy audit values;
    //  * deleted_at, deleted_by and updated_at always change together in a single statement;
    //  * the tenant condition keeps another agency's property unreachable (0 rows = "not found").
    // A bulk update bypasses the persistence context, so clearAutomatically drops any entity
    // loaded earlier in the same transaction (it would otherwise be stale) and
    // flushAutomatically writes pending changes first so none are lost by that clear.
    // Only status / images / leads are untouched: this statement never writes them.

    // Row-locking read for the EDIT path (SELECT ... FOR UPDATE). The archive / restore updates
    // below take the same row lock when they run, so the two serialize on the property row:
    //  * edit first  -> it keeps the lock until its transaction commits; a concurrent archive
    //    waits, then archives the already-updated property;
    //  * archive first -> the edit waits here, then reads the LATEST committed row (already
    //    archived) and PropertyService answers 409 instead of silently editing a hidden listing.
    // Tenant-scoped like every other lookup, so a foreign id is simply "not found" and never
    // locks or reveals another agency's row. Must be called inside a transaction.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Property p where p.id = :id and p.tenantId = :tenantId")
    Optional<Property> findByIdAndTenantIdForUpdate(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Property p set p.deletedAt = :now, p.deletedBy = :userId, p.updatedAt = :now "
            + "where p.id = :id and p.tenantId = :tenantId and p.deletedAt is null")
    int archiveIfActive(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("userId") UUID userId,
            @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Property p set p.deletedAt = null, p.deletedBy = null, p.updatedAt = :now "
            + "where p.id = :id and p.tenantId = :tenantId and p.deletedAt is not null")
    int restoreIfArchived(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("now") LocalDateTime now);

    List<Property> findAllByTenantIdAndStatusAndDeletedAtIsNull(
            UUID tenantId, String status);

    // Count form of the finder above, for figures that only need a number (avoids loading every
    // entity just to call size()).
    long countByTenantIdAndStatusAndDeletedAtIsNull(UUID tenantId, String status);

    Optional<Property> findByIdAndTenantIdAndStatusAndDeletedAtIsNull(
            UUID id, UUID tenantId, String status);

    // Batch lookup used to attach property titles to leads. Tenant-scoped so a lead can never
    // reveal another agency's property even if its propertyId were somehow foreign.
    List<Property> findAllByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

    // NOTE: the old public finders (findAllByTenantIdAndStatus / findByIdAndTenantIdAndStatus)
    // were removed on purpose: they ignored the archive flag, so any future public endpoint
    // using them would have exposed archived properties. Use the ...AndDeletedAtIsNull ones.
}
