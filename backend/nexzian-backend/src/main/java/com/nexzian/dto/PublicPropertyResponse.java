
package com.nexzian.dto;

import java.math.BigDecimal;
import java.util.UUID;

public class PublicPropertyResponse {

    private UUID id;
    private String title;
    private String description;

    private String transactionType;
    private String propertyCategory;
    private String propertyType;

    private String city;
    private String locality;

    private BigDecimal price;
    private BigDecimal areaSqft;

    private Integer bedrooms;
    private Integer bathrooms;

    public PublicPropertyResponse() {
    }

    public PublicPropertyResponse(PropertyResponse property) {
        this.id = property.getId();
        this.title = property.getTitle();
        this.description = property.getDescription();
        this.transactionType = property.getTransactionType();
        this.propertyCategory = property.getPropertyCategory();
        this.propertyType = property.getPropertyType();
        this.city = property.getCity();
        this.locality = property.getLocality();
        this.price = property.getPrice();
        this.areaSqft = property.getAreaSqft();
        this.bedrooms = property.getBedrooms();
        this.bathrooms = property.getBathrooms();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(String transactionType) {
        this.transactionType = transactionType;
    }

    public String getPropertyCategory() {
        return propertyCategory;
    }

    public void setPropertyCategory(String propertyCategory) {
        this.propertyCategory = propertyCategory;
    }

    public String getPropertyType() {
        return propertyType;
    }

    public void setPropertyType(String propertyType) {
        this.propertyType = propertyType;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getLocality() {
        return locality;
    }

    public void setLocality(String locality) {
        this.locality = locality;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getAreaSqft() {
        return areaSqft;
    }

    public void setAreaSqft(BigDecimal areaSqft) {
        this.areaSqft = areaSqft;
    }

    public Integer getBedrooms() {
        return bedrooms;
    }

    public void setBedrooms(Integer bedrooms) {
        this.bedrooms = bedrooms;
    }

    public Integer getBathrooms() {
        return bathrooms;
    }

    public void setBathrooms(Integer bathrooms) {
        this.bathrooms = bathrooms;
    }
}
