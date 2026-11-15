package com.crewparty.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A login-free identity for one open browser tab.
 * Two tabs of the same browser therefore get two different personas, which is what makes the
 * "live multi-tab presence" demo work without any account system.
 */
public class GuestSession {

    public static final String SOLO = "Solo";
    public static final String COUPLES = "Couples";
    public static final String FAMILY = "Family";
    public static final String CHILL = "Chill";
    public static final String ACTIVE = "Active";

    private String guestId;
    private String displayName;
    private String ageRole;
    private String city;
    private String neighborhood;
    private double lat;
    private double lng;
    private String format = SOLO;
    private String pace = CHILL;
    private int budgetMin;
    private int budgetMax;
    private List<String> verificationBadges = new ArrayList<>();
    private boolean publicVenuesOnly = true;
    private boolean miniGroupPriority;
    private long lastSeenEpochMs;

    public GuestSession() {
    }

    public GuestSession(String guestId, String displayName, String ageRole, String city, String neighborhood,
                        double lat, double lng, String format, String pace, int budgetMin, int budgetMax,
                        List<String> verificationBadges) {
        this.guestId = guestId;
        this.displayName = displayName;
        this.ageRole = ageRole;
        this.city = city;
        this.neighborhood = neighborhood;
        this.lat = lat;
        this.lng = lng;
        this.format = format;
        this.pace = pace;
        this.budgetMin = budgetMin;
        this.budgetMax = budgetMax;
        this.verificationBadges = new ArrayList<>(verificationBadges);
    }

    public String getGuestId() {
        return guestId;
    }

    public void setGuestId(String guestId) {
        this.guestId = guestId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getAgeRole() {
        return ageRole;
    }

    public void setAgeRole(String ageRole) {
        this.ageRole = ageRole;
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

    public List<String> getVerificationBadges() {
        return verificationBadges;
    }

    public void setVerificationBadges(List<String> verificationBadges) {
        this.verificationBadges = verificationBadges == null ? new ArrayList<>() : new ArrayList<>(verificationBadges);
    }

    public boolean isPublicVenuesOnly() {
        return publicVenuesOnly;
    }

    public void setPublicVenuesOnly(boolean publicVenuesOnly) {
        this.publicVenuesOnly = publicVenuesOnly;
    }

    public boolean isMiniGroupPriority() {
        return miniGroupPriority;
    }

    public void setMiniGroupPriority(boolean miniGroupPriority) {
        this.miniGroupPriority = miniGroupPriority;
    }

    public long getLastSeenEpochMs() {
        return lastSeenEpochMs;
    }

    public void setLastSeenEpochMs(long lastSeenEpochMs) {
        this.lastSeenEpochMs = lastSeenEpochMs;
    }
}
