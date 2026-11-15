package com.crewparty.model;

/**
 * Payload of {@code POST /api/friends/request}.
 */
public record FriendRequestBody(String fromGuestId, String toGuestId) {
}
