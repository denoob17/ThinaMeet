package com.crewparty.model;

import java.util.List;

/**
 * Everything the dashboard needs on first paint: one request, no login.
 */
public record BootstrapResponse(
        List<Friend> friends,
        List<Venue> venues,
        List<Party> openParties,
        List<VisitRecord> visitHistory
) {
}
