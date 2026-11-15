package com.crewparty.model;

/**
 * Payload of {@code POST /api/gps/seed}: the browser's approximate coordinates plus an
 * optional human-readable city name used to label the dynamically generated demo pool.
 */
public record GpsSeedRequest(double lat, double lng, String cityName) {
}
