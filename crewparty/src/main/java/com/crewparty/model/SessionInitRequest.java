package com.crewparty.model;

/**
 * Payload of {@code POST /api/session/init}. Both fields are optional: without a {@code guestId}
 * the backend mints a brand new persona, which is how two open tabs get two identities.
 */
public record SessionInitRequest(String guestId, String city) {
}
