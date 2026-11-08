package com.nexzian.dto;

/** Intentionally minimal: no ids, no password material. */
public class AgencyRegistrationResponse {

    private final String businessName;
    private final String websiteSlug;
    private final String email;

    public AgencyRegistrationResponse(
            String businessName, String websiteSlug, String email) {
        this.businessName = businessName;
        this.websiteSlug = websiteSlug;
        this.email = email;
    }

    public String getBusinessName() { return businessName; }
    public String getWebsiteSlug() { return websiteSlug; }
    public String getEmail() { return email; }
}
