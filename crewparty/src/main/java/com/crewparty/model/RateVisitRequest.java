package com.crewparty.model;

import java.util.List;

/**
 * Payload sent when the user submits a venue rating from the Visit History tab.
 */
public record RateVisitRequest(
        int rating,
        List<String> tags,
        String comment
) {

    public RateVisitRequest {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
