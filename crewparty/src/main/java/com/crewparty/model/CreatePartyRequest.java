package com.crewparty.model;

import java.util.List;

/**
 * Payload sent by the "+ Create Party" modal.
 * {@code venueId} may be blank when {@code midpointMode} is true: the crew then only picks a
 * neighborhood, a budget window and a theme, and the backend auto-selects a fair midpoint spot.
 * A blank {@code venueId} is also allowed when the host dropped a {@code spotName} on the map:
 * {@code isListedPlace} then says whether that spot is a real organization (bind/create a venue)
 * or a free-form custom location (street, courtyard, unnamed place).
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
        List<String> buddyIds,
        String guestId,
        boolean midpointMode,
        String format,
        String pace,
        boolean miniGroupPriority,
        String spotName,
        String spotCategory,
        Double spotLat,
        Double spotLng,
        boolean isListedPlace
) {

    public CreatePartyRequest {
        buddyIds = buddyIds == null ? List.of() : List.copyOf(buddyIds);
    }
}
