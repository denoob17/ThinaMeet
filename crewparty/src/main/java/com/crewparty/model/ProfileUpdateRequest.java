package com.crewparty.model;

import java.util.List;

/**
 * Payload of {@code PUT /api/profile}. Every field is optional: a {@code null} value means
 * "leave the current guest setting untouched", which keeps partial updates safe.
 */
public record ProfileUpdateRequest(
        String guestId,
        String displayName,
        String ageRole,
        String city,
        String neighborhood,
        Integer budgetMin,
        Integer budgetMax,
        String format,
        String pace,
        Boolean publicVenuesOnly,
        Boolean miniGroupPriority,
        List<String> verificationBadges,
        Double lat,
        Double lng
) {
}
