
package com.nexzian.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public class LeadResponse {

    private UUID id;
    private UUID propertyId;

    // Read-only context about the linked property, resolved server-side so the UI can show the
    // original title (and an "Archived" badge) even when the property is no longer in the
    // active property list. Null when the lead has no property.
    private String propertyTitle;
    private Boolean propertyArchived;
    // true = the lead has a propertyId but no such property exists for this agency any more
    // (legacy hard delete); false = property found (active or archived); null = no property.
    private Boolean propertyMissing;

    public Boolean getPropertyMissing() {
        return propertyMissing;
    }

    public void setPropertyMissing(Boolean propertyMissing) {
        this.propertyMissing = propertyMissing;
    }

    public String getPropertyTitle() {
        return propertyTitle;
    }

    public void setPropertyTitle(String propertyTitle) {
        this.propertyTitle = propertyTitle;
    }

    public Boolean getPropertyArchived() {
        return propertyArchived;
    }

    public void setPropertyArchived(Boolean propertyArchived) {
        this.propertyArchived = propertyArchived;
    }
    private String customerName;
    private String email;
    private String phone;
    private String source;
    private String message;
    private String status;
    private LocalDateTime followUpAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getPropertyId() {
        return propertyId;
    }

    public void setPropertyId(UUID propertyId) {
        this.propertyId = propertyId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getFollowUpAt() {
        return followUpAt;
    }

    public void setFollowUpAt(LocalDateTime followUpAt) {
        this.followUpAt = followUpAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
