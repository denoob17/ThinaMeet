package com.crewparty.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;

/**
 * A real, public place where a crew can hang out (board game cafe, gaming lounge, coworking spot...).
 * {@code avgRating} / {@code ratingCount} are recalculated live every time a visit is rated.
 */
public class Venue {

    private String id;
    private String name;
    private String category;
    private String city;
    private String neighborhood;
    private int avgCostUsd;
    private double avgRating;
    private int ratingCount;
    private List<String> tags = new ArrayList<>();
    private double lat;
    private double lng;

    /** True for a verified public place the safety filter can surface (never a private address). */
    private boolean publicSafeSpot = true;

    /** Highlighted partner venue - gets a small badge in the UI. */
    private boolean featuredPartner;

    /** Number of OPEN slots currently hosted here, refreshed by the repository after every change. */
    private int activeSlotCount;

    /** How many guests are waiting in line for a slot at this venue. */
    private int queueCount;

    /** True for a venue generated on the fly around the caller's GPS position (demo pool). */
    private boolean demoPool;

    /** Sum of every star given to this venue - kept so a re-rating does not double count. */
    private double ratingSum;

    public Venue() {
    }

    public Venue(String id, String name, String category, String city, String neighborhood,
                 int avgCostUsd, double avgRating, int ratingCount, List<String> tags,
                 double lat, double lng) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.city = city;
        this.neighborhood = neighborhood;
        this.avgCostUsd = avgCostUsd;
        this.avgRating = avgRating;
        this.ratingCount = ratingCount;
        this.tags = new ArrayList<>(tags);
        this.lat = lat;
        this.lng = lng;
        this.ratingSum = avgRating * ratingCount;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
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

    public int getAvgCostUsd() {
        return avgCostUsd;
    }

    public void setAvgCostUsd(int avgCostUsd) {
        this.avgCostUsd = avgCostUsd;
    }

    public double getAvgRating() {
        return avgRating;
    }

    public void setAvgRating(double avgRating) {
        this.avgRating = avgRating;
    }

    public int getRatingCount() {
        return ratingCount;
    }

    public void setRatingCount(int ratingCount) {
        this.ratingCount = ratingCount;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = new ArrayList<>(tags);
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

    public boolean isFeaturedPartner() {
        return featuredPartner;
    }

    public void setFeaturedPartner(boolean featuredPartner) {
        this.featuredPartner = featuredPartner;
    }

    public int getActiveSlotCount() {
        return activeSlotCount;
    }

    public void setActiveSlotCount(int activeSlotCount) {
        this.activeSlotCount = activeSlotCount;
    }

    public int getQueueCount() {
        return queueCount;
    }

    public void setQueueCount(int queueCount) {
        this.queueCount = queueCount;
    }

    public boolean isDemoPool() {
        return demoPool;
    }

    public void setDemoPool(boolean demoPool) {
        this.demoPool = demoPool;
    }

    @JsonIgnore
    public double getRatingSum() {
        return ratingSum;
    }

    public void setRatingSum(double ratingSum) {
        this.ratingSum = ratingSum;
    }
}
