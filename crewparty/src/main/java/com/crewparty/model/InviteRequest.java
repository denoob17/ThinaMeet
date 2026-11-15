package com.crewparty.model;

/**
 * Payload of {@code POST /api/invites}: one guest inviting another into a party slot.
 */
public record InviteRequest(String fromGuestId, String toGuestId, String partyId) {
}
