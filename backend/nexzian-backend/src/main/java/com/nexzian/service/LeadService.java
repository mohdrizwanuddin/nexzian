
package com.nexzian.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.nexzian.dto.LeadRequest;
import com.nexzian.dto.LeadResponse;
import com.nexzian.entity.Lead;
import com.nexzian.entity.Property;
import com.nexzian.mapper.LeadMapper;
import com.nexzian.repository.LeadRepository;
import com.nexzian.repository.PropertyRepository;

@Service
public class LeadService {

    private static final Set<String> ALLOWED_STATUSES = Set.of(
        "NEW",
        "CONTACTED",
        "VISIT_SCHEDULED",
        "NEGOTIATION",
        "CONVERTED",
        "LOST"
    );

    private final LeadRepository leadRepository;
    private final PropertyRepository propertyRepository;
    private final LeadMapper leadMapper;

    public LeadService(
            LeadRepository leadRepository,
            PropertyRepository propertyRepository,
            LeadMapper leadMapper) {
        this.leadRepository = leadRepository;
        this.propertyRepository = propertyRepository;
        this.leadMapper = leadMapper;
    }

    public LeadResponse createLead(LeadRequest request, UUID tenantId) {

        // A NEW lead may only point at an active property of this tenant.
        validateProperty(request.getPropertyId(), tenantId, null);

        Lead lead = leadMapper.toEntity(request);
        lead.setTenantId(tenantId);
        lead.setStatus("NEW");
        lead.setFollowUpAt(request.getFollowUpAt());

        Lead savedLead = leadRepository.save(lead);

        return toResponse(savedLead, tenantId);
    }

    public List<LeadResponse> getAllLeads(UUID tenantId) {

        List<Lead> leads =
                leadRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);

        // One batched, tenant-scoped property lookup for the whole list (not one per lead).
        Map<UUID, Property> properties = loadProperties(leads, tenantId);

        return leads.stream()
                .map(lead -> toResponse(lead, properties))
                .toList();
    }

    public Optional<LeadResponse> getLeadById(
            UUID id, UUID tenantId) {

        return leadRepository.findByIdAndTenantId(id, tenantId)
                .map(lead -> toResponse(lead, tenantId));
    }

    public Optional<LeadResponse> updateLead(
            UUID id, UUID tenantId, LeadRequest request) {

        return leadRepository.findByIdAndTenantId(id, tenantId)
                .map(lead -> {

                    // Keeping the lead's existing property (even if it is now archived) is
                    // always allowed so history is never lost and the lead stays editable;
                    // pointing it at a DIFFERENT property requires an active one.
                    validateProperty(
                            request.getPropertyId(), tenantId, lead.getPropertyId());

                    leadMapper.updateEntity(lead, request);

                    if (request.getStatus() != null
                            && !request.getStatus().isBlank()) {

                        String status = request.getStatus()
                                .trim()
                                .toUpperCase();

                        if (!ALLOWED_STATUSES.contains(status)) {
                            throw new IllegalArgumentException(
                                "Invalid lead status: " + status);
                        }

                        lead.setStatus(status);
                    }

                    lead.setFollowUpAt(request.getFollowUpAt());

                    Lead updatedLead = leadRepository.save(lead);

                    return toResponse(updatedLead, tenantId);
                });
    }

    public boolean deleteLead(UUID id, UUID tenantId) {

        Optional<Lead> lead =
                leadRepository.findByIdAndTenantId(id, tenantId);

        if (lead.isEmpty()) {
            return false;
        }

        leadRepository.delete(lead.get());
        return true;
    }

    public long countTotalLeads(UUID tenantId) {
        return leadRepository.countByTenantId(tenantId);
    }

    public long countLeadsByStatus(
            UUID tenantId, String status) {

        return leadRepository.countByTenantIdAndStatus(
                tenantId, status);
    }

    /**
     * Validates the property a lead is being linked to.
     *
     * Tenant isolation: the property must exist in THIS tenant (a foreign tenant's property is
     * reported as not found, never revealed). Archive rule: an archived property can no longer
     * be newly associated with a lead. The one exception is {@code currentPropertyId}, the
     * property the lead already has: resubmitting it must keep working so a lead whose property
     * was archived stays editable and keeps its historical association.
     *
     * Legacy data: before archiving existed, deleting a property hard-deleted the row and left
     * leads pointing at an id that no longer exists anywhere. The edit form re-sends that id, so
     * rejecting it would make such leads impossible to update. Resubmitting the lead's CURRENT id
     * is therefore accepted when that property is truly gone (exists in no tenant), and the
     * lead keeps the dangling link until the user clears or replaces it. This is deliberately
     * narrow: a DIFFERENT id must still exist in this tenant, and a current id that exists in
     * ANOTHER tenant is rejected, so the exemption can never be used to keep or probe a
     * cross-tenant link.
     */
    private void validateProperty(
            UUID propertyId, UUID tenantId, UUID currentPropertyId) {

        if (propertyId == null) {
            return;
        }

        boolean keepingExistingAssociation = propertyId.equals(currentPropertyId);

        Optional<Property> found =
                propertyRepository.findByIdAndTenantId(propertyId, tenantId);

        if (found.isEmpty()) {
            // existsById is only used to tell "deleted long ago" from "belongs to someone else";
            // its answer is never returned to the client.
            if (keepingExistingAssociation && !propertyRepository.existsById(propertyId)) {
                return;
            }
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Property not found for this agency");
        }

        Property property = found.get();

        if (property.isArchived() && !keepingExistingAssociation) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This property is archived and cannot be linked to a lead");
        }
    }

    // ---- Response building: attach the linked property's title / archived flag ----------------

    private LeadResponse toResponse(Lead lead, UUID tenantId) {

        Map<UUID, Property> properties = loadProperties(List.of(lead), tenantId);

        return toResponse(lead, properties);
    }

    private LeadResponse toResponse(Lead lead, Map<UUID, Property> properties) {

        LeadResponse response = leadMapper.toResponse(lead);

        // Archived properties are included on purpose: the lead keeps showing its original
        // property title (flagged archived) instead of the association appearing to vanish.
        Property property = lead.getPropertyId() == null
                ? null
                : properties.get(lead.getPropertyId());

        if (property != null) {
            response.setPropertyTitle(property.getTitle());
            response.setPropertyArchived(property.isArchived());
            response.setPropertyMissing(false);
        } else if (lead.getPropertyId() != null) {
            // The lead points at a property this tenant cannot see: it was hard-deleted before
            // archiving existed (or the id is foreign/corrupt - deliberately indistinguishable).
            // Reported separately from "archived" so the UI does not claim the property can be
            // restored; archived properties are always found above.
            response.setPropertyMissing(true);
        }

        return response;
    }

    // Loads the leads' properties in one tenant-scoped query. Scoping by tenantId guarantees a
    // lead can never expose another agency's property title.
    private Map<UUID, Property> loadProperties(List<Lead> leads, UUID tenantId) {

        Set<UUID> ids = leads.stream()
                .map(Lead::getPropertyId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (ids.isEmpty()) {
            return Map.of();
        }

        return propertyRepository.findAllByTenantIdAndIdIn(tenantId, ids)
                .stream()
                .collect(Collectors.toMap(Property::getId, Function.identity()));
    }
}
