
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
import com.nexzian.mapper.PropertyMapper;
import com.nexzian.repository.PropertyRepository;

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

    // Get all properties belonging to a tenant
    @Transactional(readOnly = true)
    public List<PropertyResponse> getAllProperties(UUID tenantId) {

        return propertyRepository.findAllByTenantId(tenantId)
                .stream()
                .map(PropertyMapper::toResponse)
                .toList();
    }

    // Get one property belonging to a tenant
    @Transactional(readOnly = true)
    public Optional<PropertyResponse> getPropertyById(
            UUID propertyId, UUID tenantId) {

        return propertyRepository.findByIdAndTenantId(propertyId, tenantId)
                .map(PropertyMapper::toResponse);
    }

    // Update an existing property
    public Optional<PropertyResponse> updateProperty(
            UUID propertyId, UUID tenantId, PropertyRequest request) {

        return propertyRepository.findByIdAndTenantId(propertyId, tenantId)
                .map(existingProperty -> {

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

    // Delete a property belonging to a tenant
    public boolean deleteProperty(UUID propertyId, UUID tenantId) {

        Optional<Property> property =
                propertyRepository.findByIdAndTenantId(propertyId, tenantId);

        if (property.isEmpty()) {
            return false;
        }

        propertyRepository.delete(property.get());

        return true;
    }

    // PUBLIC WEBSITE: Get only published properties of an agency
    @Transactional(readOnly = true)
    public List<PropertyResponse> getPublicProperties(UUID tenantId) {

        return propertyRepository
                .findAllByTenantIdAndStatus(tenantId, "PUBLISHED")
                .stream()
                .map(PropertyMapper::toResponse)
                .toList();
    }

    // PUBLIC WEBSITE: Get one published property of an agency
    @Transactional(readOnly = true)
    public Optional<PropertyResponse> getPublicPropertyById(
            UUID propertyId, UUID tenantId) {

        return propertyRepository
                .findByIdAndTenantIdAndStatus(
                        propertyId, tenantId, "PUBLISHED")
                .map(PropertyMapper::toResponse);
    }
}
