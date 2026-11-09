
package com.nexzian.mapper;

import com.nexzian.dto.PropertyRequest;
import com.nexzian.dto.PropertyResponse;
import com.nexzian.entity.Property;

public class PropertyMapper {

    // Convert request DTO into Property entity
    public static Property toEntity(PropertyRequest request) {

        Property property = new Property();

        property.setTitle(request.getTitle());
        property.setDescription(request.getDescription());
        property.setTransactionType(request.getTransactionType());
        property.setPropertyCategory(request.getPropertyCategory());
        property.setPropertyType(request.getPropertyType());
        property.setCity(request.getCity());
        property.setLocality(request.getLocality());
        property.setPrice(request.getPrice());
        property.setAreaSqft(request.getAreaSqft());
        property.setBedrooms(request.getBedrooms());
        property.setBathrooms(request.getBathrooms());
        property.setStatus(request.getStatus());

        return property;
    }

    // Convert Property entity into response DTO
    public static PropertyResponse toResponse(Property property) {

        PropertyResponse response = new PropertyResponse();

        response.setId(property.getId());
        response.setTitle(property.getTitle());
        response.setDescription(property.getDescription());
        response.setTransactionType(property.getTransactionType());
        response.setPropertyCategory(property.getPropertyCategory());
        response.setPropertyType(property.getPropertyType());
        response.setCity(property.getCity());
        response.setLocality(property.getLocality());
        response.setPrice(property.getPrice());
        response.setAreaSqft(property.getAreaSqft());
        response.setBedrooms(property.getBedrooms());
        response.setBathrooms(property.getBathrooms());
        response.setStatus(property.getStatus());
        response.setCreatedAt(property.getCreatedAt());
        response.setUpdatedAt(property.getUpdatedAt());
        response.setDeletedAt(property.getDeletedAt());

        return response;
    }
}
