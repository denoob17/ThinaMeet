package com.crewparty.model;

import java.util.List;

/**
 * Payload sent by the "+ Create Party" modal.
 */
public record CreatePartyRequest(
        String title,
        String theme,
        String city,
        String neighborhood,
        int totalSlots,
        int budgetMin,
        int budgetMax,
        String timeLabel,
        String venueId,
        List<String> buddyIds
) {

    public CreatePartyRequest {
        buddyIds = buddyIds == null ? List.of() : List.copyOf(buddyIds);
    }
}
