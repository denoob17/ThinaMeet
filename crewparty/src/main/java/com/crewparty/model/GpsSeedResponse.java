package com.crewparty.model;

import java.util.List;

/**
 * Result of {@code POST /api/gps/seed}: the venues and slots that were freshly generated
 * around the caller's coordinates (all of them flagged {@code demoPool = true}).
 */
public record GpsSeedResponse(
        String city,
        double centerLat,
        double centerLng,
        int venuesCreated,
        int partiesCreated,
        List<Venue> venues,
        List<Party> parties,
        boolean demoPool
) {
}
