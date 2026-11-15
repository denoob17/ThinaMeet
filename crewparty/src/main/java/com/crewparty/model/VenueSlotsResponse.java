package com.crewparty.model;

import java.util.List;

/**
 * Response of {@code GET /api/venues/{venueId}/slots}: the venue itself plus every OPEN slot
 * hosted there, so tapping a map pin can list all active parties in one payload.
 */
public record VenueSlotsResponse(
        Venue venue,
        List<Party> slots,
        int activeSlotCount,
        int queueCount
) {
}
