package com.crewparty.model;

/**
 * Payload of {@code POST /api/parties/{partyId}/queue} — a guest asking to wait in line
 * for a slot that is already at capacity.
 */
public record QueueRequest(String guestId, String guestName) {
}
