
package com.nexzian.dto;

public class PublicTenantResponse {

    private String businessName;
    private String websiteSlug;
    private String phone;
    private String city;
    private String locality;

    public PublicTenantResponse() {
    }

    public PublicTenantResponse(
            String businessName,
            String websiteSlug,
            String phone,
            String city,
            String locality) {

        this.businessName = businessName;
        this.websiteSlug = websiteSlug;
        this.phone = phone;
        this.city = city;
        this.locality = locality;
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
}
