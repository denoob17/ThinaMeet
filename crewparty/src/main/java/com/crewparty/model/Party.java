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
}
