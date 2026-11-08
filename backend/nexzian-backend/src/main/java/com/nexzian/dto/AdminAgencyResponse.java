package com.nexzian.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminAgencyResponse(
        UUID id,
        String businessName,
        String websiteSlug,
        String status,
        String subscriptionPlan,
        String city,
        String ownerName,
        String ownerEmail,
        long propertyCount,
        long publishedPropertyCount,
        long leadCount,
        long userCount,
        LocalDateTime createdAt) { }
