package com.crewparty.model;

/**
 * A curated low-budget weekend event (free museum day, street fair, public park meetup)
 * surfaced by the Weekly Free &amp; Low-Budget Event Radar.
 */
public class RadarEvent {

    public static final String FREE_MUSEUM = "Free Museum Day";
    public static final String LOCAL_FAIR = "Local Fair";
    public static final String PARK_MEETUP = "Public Park Meetup";

    private String id;
    private String title;
    private String city;
    private String category;
    private int costPerPerson;
    private String whenLabel;
    private String venueName;
    private double lat;
    private double lng;
    private boolean publicSafeSpot = true;

    public RadarEvent() {
    }

    public RadarEvent(String id, String title, String city, String category, int costPerPerson,
                      String whenLabel, String venueName, double lat, double lng, boolean publicSafeSpot) {
        this.id = id;
        this.title = title;
        this.city = city;
        this.category = category;
        this.costPerPerson = costPerPerson;
        this.whenLabel = whenLabel;
        this.venueName = venueName;
        this.lat = lat;
        this.lng = lng;
        this.publicSafeSpot = publicSafeSpot;
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

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public int getCostPerPerson() {
        return costPerPerson;
    }

    public void setCostPerPerson(int costPerPerson) {
        this.costPerPerson = costPerPerson;
    }

    public String getWhenLabel() {
        return whenLabel;
    }

    public void setWhenLabel(String whenLabel) {
        this.whenLabel = whenLabel;
    }

    public String getVenueName() {
        return venueName;
    }

    public void setVenueName(String venueName) {
        this.venueName = venueName;
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

    public boolean isPublicSafeSpot() {
        return publicSafeSpot;
    }

    public void setPublicSafeSpot(boolean publicSafeSpot) {
        this.publicSafeSpot = publicSafeSpot;
    }
}
