
package com.nexzian.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.nexzian.dto.LeadRequest;
import com.nexzian.dto.LeadResponse;
import com.nexzian.entity.Lead;
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

        validateProperty(request.getPropertyId(), tenantId);

        Lead lead = leadMapper.toEntity(request);
        lead.setTenantId(tenantId);
        lead.setStatus("NEW");
        lead.setFollowUpAt(request.getFollowUpAt());

        Lead savedLead = leadRepository.save(lead);

        return leadMapper.toResponse(savedLead);
    }

    public List<LeadResponse> getAllLeads(UUID tenantId) {

        return leadRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId)
                .stream()
                .map(leadMapper::toResponse)
                .toList();
    }

    public Optional<LeadResponse> getLeadById(
            UUID id, UUID tenantId) {

        return leadRepository.findByIdAndTenantId(id, tenantId)
                .map(leadMapper::toResponse);
    }

    public Optional<LeadResponse> updateLead(
            UUID id, UUID tenantId, LeadRequest request) {

        return leadRepository.findByIdAndTenantId(id, tenantId)
                .map(lead -> {

                    validateProperty(request.getPropertyId(), tenantId);

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

                    return leadMapper.toResponse(updatedLead);
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

    private void validateProperty(UUID propertyId, UUID tenantId) {

        if (propertyId == null) {
            return;
        }

        boolean propertyExists = propertyRepository
                .findByIdAndTenantId(propertyId, tenantId)
                .isPresent();

        if (!propertyExists) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Property not found for this agency"
            );
        }
    }
}
