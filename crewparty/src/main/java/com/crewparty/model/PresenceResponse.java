package com.crewparty.model;

import java.util.List;

/**
 * Live availability snapshot returned by {@code GET /api/presence}: who is online right now,
 * who already accepted you, and what is still waiting for an answer.
 */
public record PresenceResponse(
        GuestSession currentGuest,
        List<GuestSession> onlineGuests,
        List<FriendRelation> acceptedFriends,
        List<FriendRelation> pendingFriendRequests,
        List<SlotInvite> pendingSlotInvites
) {
}
