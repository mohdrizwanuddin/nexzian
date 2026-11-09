
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
@Table(name = "users")
// Database-level email rules, defined once in EmailConstraints (read its comment for the portability
// reasoning). Two rules: CANONICAL (trimmed + lowercase; declared on the column below) and
// SUPPORTED ASCII (printable ASCII 0x21-0x7E only; declared here on the class). With the
// case-sensitive unique constraint on email they make uniqueness effectively case-INSENSITIVE and
// race-safe: "A@x.com" and "a@X.com" both become "a@x.com", the second insert fails with a unique
// violation, and a direct write of "A@x.com" is refused outright.
//
// Why the ASCII rule is the ONLY class-level @Check: Hibernate consults @DialectOverride.Check only
// when a class has a SINGLE @Check. With two @Check annotations (the repeated/container form) the
// override is silently ignored and PostgreSQL would get the weaker portable expression - this was
// found by GeneratedSchemaChecksTests, which inspects the DDL Hibernate generates. So:
//  * every dialect (H2 tests) gets ASCII_PORTABLE, which covers the same characters;
//  * PostgreSQL gets the EXACT production regex ASCII_POSTGRESQL (a fresh PostgreSQL schema
//    created by Hibernate therefore carries the full rule, identical to a migrated database).
// These annotations only shape NEW schemas. An existing PostgreSQL database gets the same
// constraints from docs/r4-email-integrity-migration.sql, because ddl-auto=update never adds
// checks to a table that already exists.
@Check(name = "users_email_ascii_chk", constraints = EmailConstraints.ASCII_PORTABLE)
@DialectOverride.Check(dialect = PostgreSQLDialect.class,
        override = @Check(name = "users_email_ascii_chk", constraints = EmailConstraints.ASCII_POSTGRESQL))
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 150)
    private String fullName;

    // CANONICAL rule (trimmed + lowercase) as a named column check: see the class comment for why it
    // is declared here and not as a second class-level @Check.
    @Check(name = "users_email_canonical_chk", constraints = EmailConstraints.CANONICAL)
    @Column(nullable = false, unique = true, length = 150)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, length = 30)
    private String role = "OWNER";

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

    // Getters and setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    // Normalizing AND enforcing the supported-address policy at the entity boundary means no code
    // path (present or future) can store a non-canonical or unsupported (non-ASCII, whitespace)
    // email through JPA: it fails with UnsupportedEmailException before reaching the database. The
    // services also normalize explicitly because they must compare the same value before saving.
    public void setEmail(String email) {
        this.email = EmailNormalizer.normalizeForStorage(email);
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
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
