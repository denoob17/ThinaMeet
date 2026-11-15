package com.crewparty.model;

/**
 * An invitation sent by one guest to another to take a free slot in a party.
 * {@code status} is {@code PENDING} → {@code ACCEPTED} or {@code DECLINED}.
 */
public class SlotInvite {

    public static final String PENDING = "PENDING";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String DECLINED = "DECLINED";

    private String id;
    private String fromGuestId;
    private String fromName;
    private String toGuestId;
    private String partyId;
    private String partyTitle;
    private String venueName;
    private String status = PENDING;
    private long createdAtMs;

    public SlotInvite() {
    }

    public SlotInvite(String id, String fromGuestId, String fromName, String toGuestId,
                      String partyId, String partyTitle, String venueName,
                      String status, long createdAtMs) {
        this.id = id;
        this.fromGuestId = fromGuestId;
        this.fromName = fromName;
        this.toGuestId = toGuestId;
        this.partyId = partyId;
        this.partyTitle = partyTitle;
        this.venueName = venueName;
        this.status = status;
        this.createdAtMs = createdAtMs;
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

    public String getPartyId() {
        return partyId;
    }

    public void setPartyId(String partyId) {
        this.partyId = partyId;
    }

    public String getPartyTitle() {
        return partyTitle;
    }

    public void setPartyTitle(String partyTitle) {
        this.partyTitle = partyTitle;
    }

    public String getVenueName() {
        return venueName;
    }

    public void setVenueName(String venueName) {
        this.venueName = venueName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public void setCreatedAtMs(long createdAtMs) {
        this.createdAtMs = createdAtMs;
    }
}
