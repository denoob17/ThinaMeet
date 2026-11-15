package com.crewparty.model;

/**
 * A live friend request between two guest sessions.
 * {@code status} is {@code PENDING} until the target accepts, then {@code ACCEPTED}.
 */
public class FriendRelation {

    public static final String PENDING = "PENDING";
    public static final String ACCEPTED = "ACCEPTED";

    private String id;
    private String fromGuestId;
    private String fromName;
    private String toGuestId;
    private String toName;
    private String status = PENDING;

    public FriendRelation() {
    }

    public FriendRelation(String id, String fromGuestId, String fromName,
                          String toGuestId, String toName, String status) {
        this.id = id;
        this.fromGuestId = fromGuestId;
        this.fromName = fromName;
        this.toGuestId = toGuestId;
        this.toName = toName;
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFromGuestId() {
        return fromGuestId;
    }

    public void setFromGuestId(String fromGuestId) {
        this.fromGuestId = fromGuestId;
    }

    public String getFromName() {
        return fromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
    }

    public String getToGuestId() {
        return toGuestId;
    }

    public void setToGuestId(String toGuestId) {
        this.toGuestId = toGuestId;
    }

    public String getToName() {
        return toName;
    }

    public void setToName(String toName) {
        this.toName = toName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
