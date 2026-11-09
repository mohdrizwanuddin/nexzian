
package com.nexzian.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "properties")
// Write only the columns that actually changed. Without this, an edit that loaded the property
// BEFORE a concurrent archive/restore would write its stale deleted_at / deleted_by back and
// silently undo that transition (a hidden listing could reappear publicly). With it, an edit
// never touches the archive columns unless they themselves changed, and those only change via
// the atomic updates in PropertyRepository. Needs no schema change.
@DynamicUpdate
public class Property {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "transaction_type", nullable = false, length = 20)
    private String transactionType;

    @Column(name = "property_category", nullable = false, length = 50)
    private String propertyCategory;

    @Column(name = "property_type", nullable = false, length = 50)
    private String propertyType;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(length = 100)
    private String locality;

    @Column(precision = 14, scale = 2)
    private BigDecimal price;

    private Integer bedrooms;

    private Integer bathrooms;

    @Column(name = "area_sqft", precision = 10, scale = 2)
    private BigDecimal areaSqft;

    @Column(nullable = false, length = 30)
    private String status = "DRAFT";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ---- Soft delete (archive) -------------------------------------------------------------
    // A property is "archived" when deleted_at is set. This is deliberately separate from
    // `status`: SOLD / RENTED / AVAILABLE / DRAFT are business states an archived property can
    // still be in, and status is free text, so it cannot be trusted as a safety flag. Both
    // columns are nullable, so existing rows read as active without any backfill, and
    // hibernate ddl-auto=update can add them with a plain ALTER TABLE.
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // The user who archived the property (audit trail). Plain UUID, no foreign key, matching
    // how tenant_id is stored on this table, so archiving never depends on the user row.
    @Column(name = "deleted_by")
    private UUID deletedBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    // Getters and setters

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
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

    public BigDecimal getAreaSqft() {
        return areaSqft;
    }

    public void setAreaSqft(BigDecimal areaSqft) {
        this.areaSqft = areaSqft;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public UUID getDeletedBy() {
        return deletedBy;
    }

    public boolean isArchived() {
        return deletedAt != null;
    }

    // Deliberately NO setters / archive() / restore() for deletedAt and deletedBy: the archive
    // state may only change through the atomic conditional updates in PropertyRepository
    // (archiveIfActive / restoreIfArchived), which keep the two fields paired and make
    // concurrent requests safe. An entity-level setter would reintroduce a read-then-save race.
}
