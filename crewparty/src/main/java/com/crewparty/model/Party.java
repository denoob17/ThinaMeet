package com.crewparty.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A hangout the user wants to fill with 2-3 extra people.
 * {@code status} is {@code OPEN} while the party is looking for players and {@code COMPLETED}
 * once it has been moved into the visit history.
 */
public class Party {

    public static final String OPEN = "OPEN";
    public static final String COMPLETED = "COMPLETED";

    private String id;
    private String title;
    private String theme;
    private String city;
    private String neighborhood;
    private int totalSlots;
    private int filledSlots;
    private int budgetMin;
    private int budgetMax;
    private String timeLabel;
    private String venueId;
    private String venueName;
    private List<String> members = new ArrayList<>();
    private List<String> icebreakers = new ArrayList<>();
    private String status = OPEN;

    /** Recomputed by the match service for the current search filters (0-100). */
    private int fitScore;

    /** Human-readable breakdown of {@link #fitScore}, recomputed with every search. */
    private List<String> matchReasons = new ArrayList<>();

    /** True when the creator left the venue unselected and asked for a fair auto-midpoint pick. */
    private boolean midpointMode;

    /** Effective party coordinates: the chosen venue, or the running geographic midpoint when in midpoint mode. */
    private double lat;
    private double lng;

    /** Guest-session ids of everyone who joined (host first) - the basis of the midpoint average. */
    private List<String> memberGuestIds = new ArrayList<>();

    /** Display names of the guest members, kept in step with {@link #members} for the guest flow. */
    private List<String> memberNames = new ArrayList<>();

    /** Guests who confirmed their safety check-in at the public spot. */
    private List<String> checkedInGuestIds = new ArrayList<>();

    /** Guests waiting for a slot because the party is already at capacity. */
    private List<String> queueGuestNames = new ArrayList<>();

    /** Set by the creator when they prefer tight 3-4 person crews. */
    private boolean miniGroupPriority;

    /** Trust signals surfaced to the UI, e.g. {@code "ID Verified"}, {@code "LinkedIn Shared"}. */
    private List<String> trustBadges = new ArrayList<>();

    /** True for venues/slots generated on the fly around the caller's GPS position. */
    private boolean demoPool;

    /** Group format the slot is aimed at: {@code Solo}, {@code Couples} or {@code Family}. */
    private String format = "Solo";

    /** Energy level of the hangout: {@code Chill} or {@code Active}. */
    private String pace = "Chill";

    /** True when the slot price range fully fits the caller's budget window. */
    private boolean budgetCompliant;

    /** Haversine distance in km from the requesting guest to the slot, 0 when unknown. */
    private double distanceKm;

    public Party() {
    }

    public Party(String id, String title, String theme, String city, String neighborhood,
                 int totalSlots, int filledSlots, int budgetMin, int budgetMax, String timeLabel,
                 String venueId, String venueName, List<String> members, List<String> icebreakers,
                 String status) {
        this.id = id;
        this.title = title;
        this.theme = theme;
        this.city = city;
        this.neighborhood = neighborhood;
        this.totalSlots = totalSlots;
        this.filledSlots = filledSlots;
        this.budgetMin = budgetMin;
        this.budgetMax = budgetMax;
        this.timeLabel = timeLabel;
        this.venueId = venueId;
        this.venueName = venueName;
        this.members = new ArrayList<>(members);
        this.icebreakers = new ArrayList<>(icebreakers);
        this.status = status;
    }

    public int getOpenSlots() {
        return Math.max(0, totalSlots - filledSlots);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getNeighborhood() {
        return neighborhood;
    }

    public void setNeighborhood(String neighborhood) {
        this.neighborhood = neighborhood;
    }

    public int getTotalSlots() {
        return totalSlots;
    }

    public void setTotalSlots(int totalSlots) {
        this.totalSlots = totalSlots;
    }

    public int getFilledSlots() {
        return filledSlots;
    }

    public void setFilledSlots(int filledSlots) {
        this.filledSlots = filledSlots;
    }

    public int getBudgetMin() {
        return budgetMin;
    }

    public void setBudgetMin(int budgetMin) {
        this.budgetMin = budgetMin;
    }

    public int getBudgetMax() {
        return budgetMax;
    }

    public void setBudgetMax(int budgetMax) {
        this.budgetMax = budgetMax;
    }

    public String getTimeLabel() {
        return timeLabel;
    }

    public void setTimeLabel(String timeLabel) {
        this.timeLabel = timeLabel;
    }

    public String getVenueId() {
        return venueId;
    }

    public void setVenueId(String venueId) {
        this.venueId = venueId;
    }

    public String getVenueName() {
        return venueName;
    }

    public void setVenueName(String venueName) {
        this.venueName = venueName;
    }

    public List<String> getMembers() {
        return members;
    }

    public void setMembers(List<String> members) {
        this.members = new ArrayList<>(members);
    }

    public List<String> getIcebreakers() {
        return icebreakers;
    }

    public void setIcebreakers(List<String> icebreakers) {
        this.icebreakers = new ArrayList<>(icebreakers);
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getFitScore() {
        return fitScore;
    }

    public void setFitScore(int fitScore) {
        this.fitScore = fitScore;
    }

    public List<String> getMatchReasons() {
        return matchReasons;
    }

    public void setMatchReasons(List<String> matchReasons) {
        this.matchReasons = new ArrayList<>(matchReasons);
    }

    public boolean isMidpointMode() {
        return midpointMode;
    }

    public void setMidpointMode(boolean midpointMode) {
        this.midpointMode = midpointMode;
    }

    public double getLat() {
        return lat;
    }

    public void setLat(double lat) {
        this.lat = lat;
    }

    public double getLng() {
        return lng;
    }

    public void setLng(double lng) {
        this.lng = lng;
    }

    public List<String> getMemberGuestIds() {
        return memberGuestIds;
    }

    public void setMemberGuestIds(List<String> memberGuestIds) {
        this.memberGuestIds = memberGuestIds == null ? new ArrayList<>() : new ArrayList<>(memberGuestIds);
    }

    public List<String> getMemberNames() {
        return memberNames;
    }

    public void setMemberNames(List<String> memberNames) {
        this.memberNames = memberNames == null ? new ArrayList<>() : new ArrayList<>(memberNames);
    }

    public List<String> getCheckedInGuestIds() {
        return checkedInGuestIds;
    }

    public void setCheckedInGuestIds(List<String> checkedInGuestIds) {
        this.checkedInGuestIds = checkedInGuestIds == null ? new ArrayList<>() : new ArrayList<>(checkedInGuestIds);
    }

    public List<String> getQueueGuestNames() {
        return queueGuestNames;
    }

    public void setQueueGuestNames(List<String> queueGuestNames) {
        this.queueGuestNames = queueGuestNames == null ? new ArrayList<>() : new ArrayList<>(queueGuestNames);
    }

    public boolean isMiniGroupPriority() {
        return miniGroupPriority;
    }

    public void setMiniGroupPriority(boolean miniGroupPriority) {
        this.miniGroupPriority = miniGroupPriority;
    }

    public List<String> getTrustBadges() {
        return trustBadges;
    }

    public void setTrustBadges(List<String> trustBadges) {
        this.trustBadges = trustBadges == null ? new ArrayList<>() : new ArrayList<>(trustBadges);
    }

    public boolean isDemoPool() {
        return demoPool;
    }

    public void setDemoPool(boolean demoPool) {
        this.demoPool = demoPool;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getPace() {
        return pace;
    }

    public void setPace(String pace) {
        this.pace = pace;
    }

    public boolean isBudgetCompliant() {
        return budgetCompliant;
    }

    public void setBudgetCompliant(boolean budgetCompliant) {
        this.budgetCompliant = budgetCompliant;
    }

    public double getDistanceKm() {
        return distanceKm;
    }

    public void setDistanceKm(double distanceKm) {
        this.distanceKm = distanceKm;
    }
}
