
package com.nexzian.mapper;

import org.springframework.stereotype.Component;

import com.nexzian.dto.LeadRequest;
import com.nexzian.dto.LeadResponse;
import com.nexzian.entity.Lead;

@Component
public class LeadMapper {

    public Lead toEntity(LeadRequest request) {
        Lead lead = new Lead();

        lead.setPropertyId(request.getPropertyId());
        lead.setCustomerName(request.getCustomerName());
        lead.setEmail(request.getEmail());
        lead.setPhone(request.getPhone());
        lead.setSource(request.getSource());
        lead.setMessage(request.getMessage());

        return lead;
    }

    public void updateEntity(Lead lead, LeadRequest request) {
        lead.setPropertyId(request.getPropertyId());
        lead.setCustomerName(request.getCustomerName());
        lead.setEmail(request.getEmail());
        lead.setPhone(request.getPhone());
        lead.setSource(request.getSource());
        lead.setMessage(request.getMessage());
    }

    public LeadResponse toResponse(Lead lead) {
        LeadResponse response = new LeadResponse();

        response.setId(lead.getId());
        response.setPropertyId(lead.getPropertyId());
        response.setCustomerName(lead.getCustomerName());
        response.setEmail(lead.getEmail());
        response.setPhone(lead.getPhone());
        response.setSource(lead.getSource());
        response.setMessage(lead.getMessage());
        response.setStatus(lead.getStatus());
        response.setFollowUpAt(lead.getFollowUpAt());
        response.setCreatedAt(lead.getCreatedAt());
        response.setUpdatedAt(lead.getUpdatedAt());

        return response;
    }
}
