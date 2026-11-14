package com.crewparty.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A hangout that already happened. {@code rating} stays {@code null} until the user rates the venue,
 * which is exactly what the "Complete & Rate Visit" demo button produces.
 */
public class VisitRecord {

    private String id;
    private String partyId;
    private String partyTitle;
    private String theme;
    private String venueId;
    private String venueName;
    private String visitedDate;
    private List<String> attendees = new ArrayList<>();
    private Integer rating;
    private List<String> selectedTags = new ArrayList<>();
    private String reviewComment = "";

    public VisitRecord() {
    }

    public VisitRecord(String id, String partyId, String partyTitle, String theme, String venueId,
                       String venueName, String visitedDate, List<String> attendees,
                       Integer rating, List<String> selectedTags, String reviewComment) {
        this.id = id;
        this.partyId = partyId;
        this.partyTitle = partyTitle;
        this.theme = theme;
        this.venueId = venueId;
        this.venueName = venueName;
        this.visitedDate = visitedDate;
        this.attendees = new ArrayList<>(attendees);
        this.rating = rating;
        this.selectedTags = new ArrayList<>(selectedTags);
        this.reviewComment = reviewComment;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
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

    public String getVisitedDate() {
        return visitedDate;
    }

    public void setVisitedDate(String visitedDate) {
        this.visitedDate = visitedDate;
    }

    public List<String> getAttendees() {
        return attendees;
    }

    public void setAttendees(List<String> attendees) {
        this.attendees = new ArrayList<>(attendees);
    }

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    public List<String> getSelectedTags() {
        return selectedTags;
    }

    public void setSelectedTags(List<String> selectedTags) {
        this.selectedTags = new ArrayList<>(selectedTags);
    }

    public String getReviewComment() {
        return reviewComment;
    }

    public void setReviewComment(String reviewComment) {
        this.reviewComment = reviewComment;
    }
}
