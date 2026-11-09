
package com.nexzian.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.Check;
import org.hibernate.annotations.DialectOverride;
import org.hibernate.dialect.PostgreSQLDialect;

import com.nexzian.util.EmailNormalizer;

@Entity
@Table(name = "tenants")
// Database-level email rules for the agency's contact address: the same two rules, definitions and
// PostgreSQL override as User (see the long comment there and EmailConstraints). The ASCII rule must
// stay the only class-level @Check so that the PostgreSQL override is honoured; the canonical rule is
// declared on the email column. Existing PostgreSQL tables get both from
// docs/r4-email-integrity-migration.sql.
@Check(name = "tenants_email_ascii_chk", constraints = EmailConstraints.ASCII_PORTABLE)
@DialectOverride.Check(dialect = PostgreSQLDialect.class,
        override = @Check(name = "tenants_email_ascii_chk", constraints = EmailConstraints.ASCII_POSTGRESQL))
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 150)
    private String businessName;

    @Column(name = "website_slug", unique = true, length = 150)
    private String websiteSlug;

    // CANONICAL rule (trimmed + lowercase) as a named column check: see the class comment for why it
    // is declared here and not as a second class-level @Check.
    @Check(name = "tenants_email_canonical_chk", constraints = EmailConstraints.CANONICAL)
    @Column(nullable = false, unique = true, length = 150)
    private String email;

    @Column(length = 20)
    private String phone;

    @Column(length = 100)
    private String city;

    @Column(length = 100)
    private String locality;

    @Column(nullable = false, length = 30)
    private String subscriptionPlan = "FREE";

    @Column(nullable = false, length = 30)
    private String status = "ACTIVE";

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getBusinessName() {
        return businessName;
    }

    public void setBusinessName(String businessName) {
        this.businessName = businessName;
    }

    public String getWebsiteSlug() {
        return websiteSlug;
    }

    public void setWebsiteSlug(String websiteSlug) {
        this.websiteSlug = websiteSlug;
    }

    public String getEmail() {
        return email;
    }

    // Same canonical-email rule as User (see EmailNormalizer); a tenant's email is the agency's
    // contact address and is unique across agencies.
    public void setEmail(String email) {
        this.email = EmailNormalizer.normalizeForStorage(email);
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
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

    public String getSubscriptionPlan() {
        return subscriptionPlan;
    }

    public void setSubscriptionPlan(String subscriptionPlan) {
        this.subscriptionPlan = subscriptionPlan;
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
}
