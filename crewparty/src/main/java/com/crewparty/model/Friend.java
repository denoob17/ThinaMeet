package com.crewparty.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A person the user can hang out with.
 * {@code status} is one of {@code CORE_BUDDY}, {@code FRIEND} or {@code DISCOVER}.
 */
public class Friend {

    public static final String CORE_BUDDY = "CORE_BUDDY";
    public static final String FRIEND = "FRIEND";
    public static final String DISCOVER = "DISCOVER";

    private String id;
    private String name;
    private String ageRole;
    private String city;
    private List<String> themes = new ArrayList<>();
    private String status = DISCOVER;
    private boolean verified;

    public Friend() {
    }

    public Friend(String id, String name, String ageRole, String city,
                  List<String> themes, String status, boolean verified) {
        this.id = id;
        this.name = name;
        this.ageRole = ageRole;
        this.city = city;
        this.themes = new ArrayList<>(themes);
        this.status = status;
        this.verified = verified;
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

    public List<String> getThemes() {
        return themes;
    }

    public void setThemes(List<String> themes) {
        this.themes = new ArrayList<>(themes);
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isVerified() {
        return verified;
    }

    public void setVerified(boolean verified) {
        this.verified = verified;
    }
}
