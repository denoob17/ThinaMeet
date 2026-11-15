package com.crewparty.model;

/**
 * Payload of {@code POST /api/parties/{partyId}/checkin} — the safety check-in that tells
 * the rest of the crew that a guest actually arrived at the public spot.
 */
public record CheckInRequest(String guestId) {
}
