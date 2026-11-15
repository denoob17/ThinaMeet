package com.crewparty.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level contract tests: they pin the status codes and JSON field names the read-only
 * frontend in {@code static/js/api.js} expects, so a backend refactor cannot silently break it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthReportsUp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void bootstrapReturnsTheFourDocumentedCollections() throws Exception {
        mockMvc.perform(get("/api/bootstrap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friends").isArray())
                .andExpect(jsonPath("$.venues").isArray())
                .andExpect(jsonPath("$.openParties").isArray())
                .andExpect(jsonPath("$.visitHistory").isArray());
    }

    @Test
    void createPartyAnswers201WithGeneratedIcebreakers() throws Exception {
        String payload = """
                {"title":"Friday Catan Crew","theme":"Board Games","city":"Chicago",
                 "neighborhood":"Wicker Park","totalSlots":4,"budgetMin":10,"budgetMax":30,
                 "timeLabel":"Fri 7:00 PM","venueId":"v1","buddyIds":["f2"]}
                """;

        mockMvc.perform(post("/api/parties").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.icebreakers").isArray())
                .andExpect(jsonPath("$.fitScore").isNumber());
    }

    @Test
    void createPartyBindsMapSpotFieldsFromJson() throws Exception {
        String payload = """
                {"title":"Overpass Cafe Crew","theme":"Board Games","city":"Astana",
                 "neighborhood":"Downtown","totalSlots":4,"budgetMin":10,"budgetMax":30,
                 "timeLabel":"Sat 4:00 PM","venueId":"","spotName":"Corner Coffee & Trivia",
                 "spotCategory":"Cafe","spotLat":51.128,"spotLng":71.43,"isListedPlace":true}
                """;

        mockMvc.perform(post("/api/parties").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.venueId").isNotEmpty())
                .andExpect(jsonPath("$.venueName").value("Corner Coffee & Trivia"));
    }

    @Test
    void sessionInitAndPresenceRoundTrip() throws Exception {
        MvcResult init = mockMvc.perform(post("/api/session/init")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guestId").exists())
                .andExpect(jsonPath("$.displayName").exists())
                .andReturn();

        String guestId = com.jayway.jsonpath.JsonPath.read(init.getResponse().getContentAsString(), "$.guestId");

        mockMvc.perform(get("/api/presence").param("guestId", guestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentGuest.guestId").value(guestId))
                .andExpect(jsonPath("$.onlineGuests").isArray())
                .andExpect(jsonPath("$.acceptedFriends").isArray())
                .andExpect(jsonPath("$.pendingFriendRequests").isArray())
                .andExpect(jsonPath("$.pendingSlotInvites").isArray());
    }

    @Test
    void searchAcceptsTheExtendedFilterSet() throws Exception {
        mockMvc.perform(get("/api/search")
                        .param("city", "Chicago")
                        .param("budgetMin", "0")
                        .param("budgetMax", "30")
                        .param("format", "All")
                        .param("pace", "All")
                        .param("publicSafeOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parties").isArray())
                .andExpect(jsonPath("$.players").isArray())
                .andExpect(jsonPath("$.venues").isArray());
    }

    @Test
    void gpsSeedAnswersWithADemoPool() throws Exception {
        String payload = "{\"lat\":41.8781,\"lng\":-87.6298,\"cityName\":\"Chicago\"}";

        mockMvc.perform(post("/api/gps/seed").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("Chicago"))
                .andExpect(jsonPath("$.demoPool").value(true))
                .andExpect(jsonPath("$.venues").isArray())
                .andExpect(jsonPath("$.parties").isArray());
    }

    @Test
    void venueSlotsReturns404ForAnUnknownVenue() throws Exception {
        mockMvc.perform(get("/api/venues/nope/slots"))
                .andExpect(status().isNotFound());
    }

    @Test
    void profileRequiresAGuestId() throws Exception {
        mockMvc.perform(put("/api/profile").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Nobody\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void leavePartyRemovesTheGuestFromTheRoster() throws Exception {
        String create = """
                {"title":"Leave Contract Test","theme":"Board Games","city":"Chicago",
                 "neighborhood":"Wicker Park","totalSlots":4,"budgetMin":10,"budgetMax":30,
                 "timeLabel":"Fri 7:00 PM","venueId":"v1"}
                """;
        MvcResult created = mockMvc.perform(post("/api/parties")
                        .contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isCreated())
                .andReturn();
        String partyId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        MvcResult init = mockMvc.perform(post("/api/session/init")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        String guestId = com.jayway.jsonpath.JsonPath.read(init.getResponse().getContentAsString(), "$.guestId");

        mockMvc.perform(post("/api/parties/" + partyId + "/join")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"guestId\":\"" + guestId + "\"}"))
                .andExpect(status().isOk());

        MvcResult left = mockMvc.perform(post("/api/parties/" + partyId + "/leave")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"guestId\":\"" + guestId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(partyId))
                .andReturn();

        java.util.List<String> members = com.jayway.jsonpath.JsonPath.read(
                left.getResponse().getContentAsString(), "$.memberGuestIds");
        org.assertj.core.api.Assertions.assertThat(members).doesNotContain(guestId);
    }
}
