package com.crewparty.model;

/**
 * Payload of {@code POST /api/invites/{inviteId}/respond}.
 * {@code action} is {@code ACCEPT} or {@code DECLINE}; accepting drops the guest into the party.
 */
public record InviteRespondRequest(String action) {
}
