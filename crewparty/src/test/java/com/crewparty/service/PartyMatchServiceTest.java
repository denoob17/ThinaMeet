package com.crewparty.service;

import com.crewparty.model.CreatePartyRequest;
import com.crewparty.model.FriendRelation;
import com.crewparty.model.GpsSeedRequest;
import com.crewparty.model.GpsSeedResponse;
import com.crewparty.model.GuestSession;
import com.crewparty.model.Party;
import com.crewparty.model.PresenceResponse;
import com.crewparty.model.ProfileUpdateRequest;
import com.crewparty.model.QueueRequest;
import com.crewparty.model.RadarEvent;
import com.crewparty.model.SessionInitRequest;
import com.crewparty.model.SlotInvite;
import com.crewparty.model.Venue;
import com.crewparty.model.VenueSlotsResponse;
import com.crewparty.model.VisitRecord;
import com.crewparty.repository.InMemoryStoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic unit tests for the seven extended features. Every test runs against a fresh
 * repository + service, so the assertions never depend on another test's mutations.
 */
class PartyMatchServiceTest {

    private InMemoryStoreRepository repository;
    private PartyMatchService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryStoreRepository();
        // Empty key -> the AI service always uses its offline fallback list, no network in tests.
        service = new PartyMatchService(repository, new AiIcebreakerService(""));
    }

    // ----------------------------------------------------------------- helpers

    private GuestSession guest(String id, String name, String city, double lat, double lng, int budgetMax) {
        GuestSession session = new GuestSession(id, name, "30 • Tester", city, "Downtown", lat, lng,
                GuestSession.SOLO, GuestSession.CHILL, 0, budgetMax, List.of("ID Verified"));
        session.setLastSeenEpochMs(System.currentTimeMillis());
        return repository.saveGuest(session);
    }

    private Venue venue(String id, String name, String city, int cost, double lat, double lng, boolean publicSafe) {
        Venue venue = new Venue(id, name, "Board Game Cafe", city, "Downtown", cost, 4.5, 20,
                List.of("Great Table Space"), lat, lng);
        venue.setPublicSafeSpot(publicSafe);
        venue.setDemoPool(false);
        repository.saveVenue(venue);
        return venue;
    }

    private Party party(String id, String city, String venueId, String venueName, int total, int filled,
                        int budgetMin, int budgetMax, String format, String pace,
                        List<String> memberGuestIds, boolean miniGroup) {
        Party party = new Party(id, "Test Party", "Board Games", city, "Downtown", total, filled,
                budgetMin, budgetMax, "Sat 4:00 PM", venueId, venueName,
                List.of("You (Host)"), List.of("Q1", "Q2", "Q3"), Party.OPEN);
        party.setFormat(format);
        party.setPace(pace);
        party.setMemberGuestIds(memberGuestIds);
        party.setTrustBadges(List.of("ID Verified"));
        party.setMiniGroupPriority(miniGroup);
        repository.saveParty(party);
        return party;
    }

    private static int status(ResponseStatusException exception) {
        return exception.getStatusCode().value();
    }

    // ------------------------------------------------------- 5 · fit scoring

    @Test
    void fitScoreIsDeterministicAndFullyExplained() {
        Party match = party("p100", "Chicago", "v1", "Snakes & Lattes", 4, 2,
                10, 30, GuestSession.SOLO, GuestSession.CHILL, List.of(), false);

        PartyMatchService.FitResult best = service.calculateFitScore(match, "Board Games", "Chicago", 30);

        assertThat(best.score()).isEqualTo(100); // theme 45 + city 25 + budget 20 + open slots 10
        assertThat(best.reasons()).isNotEmpty();

        PartyMatchService.FitResult worst = service.calculateFitScore(match, "Trivia & Social", "Austin", 5);

        // theme 0 + city 0 + partial budget (10 <= 5 + 10) 10 + open slots 10
        assertThat(worst.score()).isEqualTo(20);
    }

    @Test
    void scorePartyStoresTheNumberAndTheReasonsOnTheParty() {
        Party match = party("p101", "Chicago", "v1", "Snakes & Lattes", 4, 1,
                10, 30, GuestSession.SOLO, GuestSession.CHILL, List.of(), false);

        service.scoreParty(match, "Board Games", "Chicago", 0);

        assertThat(match.getFitScore()).isEqualTo(100);
        assertThat(match.getMatchReasons()).isNotEmpty();
    }

    @Test
    void searchSupportsARealZeroDollarBudgetWindow() {
        venue("vFree", "Free Park Meetup", "Chicago", 0, 41.88, -87.63, true);
        venue("vPaid", "Board Game Cafe", "Chicago", 25, 41.89, -87.63, true);
        Party free = party("pFree", "Chicago", "vFree", "Free Park Meetup", 4, 1,
                0, 0, GuestSession.SOLO, GuestSession.CHILL, List.of(), false);
        Party paid = party("pPaid", "Chicago", "vPaid", "Board Game Cafe", 4, 1,
                20, 40, GuestSession.SOLO, GuestSession.CHILL, List.of(), false);

        PartyMatchService.SearchOutcome outcome = service.search(null, "Chicago", null, null,
                0, 0, "All", "All", false, false, false, false);

        assertThat(outcome.parties()).extracting(Party::getId).containsExactly(free.getId());
        assertThat(free.isBudgetCompliant()).isTrue();
        assertThat(outcome.parties()).doesNotContain(paid);
    }

    @Test
    void searchAppliesStrictFormatAndPaceFilters() {
        venue("v1", "Spot", "Chicago", 20, 41.88, -87.63, true);
        party("pSolo", "Chicago", "v1", "Spot", 4, 1, 10, 30,
                GuestSession.SOLO, GuestSession.CHILL, List.of(), false);
        Party couples = party("pCouple", "Chicago", "v1", "Spot", 4, 1, 10, 30,
                GuestSession.COUPLES, GuestSession.ACTIVE, List.of(), false);

        PartyMatchService.SearchOutcome outcome = service.search(null, "Chicago", null, null,
                -1, -1, GuestSession.COUPLES, GuestSession.ACTIVE, false, false, false, false);

        assertThat(outcome.parties()).extracting(Party::getId).containsExactly(couples.getId());
    }

    @Test
    void searchFriendsOnlyKeepsPartiesWithAnAcceptedFriendInTheRoster() {
        venue("v1", "Spot", "Chicago", 20, 41.88, -87.63, true);
        guest("gA", "Ada", "Chicago", 41.88, -87.63, 40);
        guest("gB", "Ben", "Chicago", 41.88, -87.63, 40);
        party("pFriend", "Chicago", "v1", "Spot", 4, 1, 10, 30,
                GuestSession.SOLO, GuestSession.CHILL, List.of("gB"), false);
        repository.saveFriendRelation(new FriendRelation("fr1", "gA", "Ada", "gB", "Ben",
                FriendRelation.ACCEPTED));

        PartyMatchService.SearchOutcome withFriend = service.search("gA", "Chicago", null, null,
                -1, -1, "All", "All", true, false, false, false);
        PartyMatchService.SearchOutcome noFriend = service.search("gB", "Chicago", null, null,
                -1, -1, "All", "All", true, false, false, false);

        assertThat(withFriend.parties()).extracting(Party::getId).containsExactly("pFriend");
        assertThat(noFriend.parties()).isEmpty();
    }

    // ------------------------------------------------ 1 · multi-tab sessions

    @Test
    void initSessionMintsDistinctPersonasForTwoTabs() {
        GuestSession tabOne = service.initSession(new SessionInitRequest(null, null));
        GuestSession tabTwo = service.initSession(new SessionInitRequest(null, null));

        assertThat(tabOne.getGuestId()).isNotEqualTo(tabTwo.getGuestId());
        assertThat(tabOne.getDisplayName()).isNotEqualTo(tabTwo.getDisplayName());

        // Re-initialising a known guestId is idempotent and just refreshes the heartbeat.
        GuestSession again = service.initSession(new SessionInitRequest(tabOne.getGuestId(), null));
        assertThat(again.getGuestId()).isEqualTo(tabOne.getGuestId());

        // An unknown guestId still gets a fresh persona under that id.
        GuestSession custom = service.initSession(new SessionInitRequest("my-tab", "Chicago"));
        assertThat(custom.getGuestId()).isEqualTo("my-tab");
        assertThat(custom.getCity()).isEqualTo("Chicago");
    }

    @Test
    void presenceRefreshesTheHeartbeatAndDropsStaleTabs() {
        GuestSession live = service.initSession(new SessionInitRequest(null, null));
        GuestSession stale = guest("stale", "Old", "Chicago", 41.88, -87.63, 30);
        stale.setLastSeenEpochMs(System.currentTimeMillis() - 10 * 60 * 1000L);

        PresenceResponse presence = service.presence(live.getGuestId());

        assertThat(presence.currentGuest()).isNotNull();
        assertThat(presence.currentGuest().getGuestId()).isEqualTo(live.getGuestId());
        assertThat(presence.currentGuest().getLastSeenEpochMs()).isGreaterThan(0);
        assertThat(repository.findGuest("stale")).isEmpty();
    }

    // --------------------------------------------------- 2 · GPS dynamic seed

    @Test
    void gpsSeedBuildsARealisticPublicPoolAndDeduplicatesRepeats() {
        GpsSeedResponse first = service.gpsSeed(new GpsSeedRequest(41.8781, -87.6298, "Chicago"));

        assertThat(first.demoPool()).isTrue();
        assertThat(first.venuesCreated()).isEqualTo(first.venues().size()).isEqualTo(7);
        assertThat(first.partiesCreated()).isEqualTo(first.parties().size()).isEqualTo(7);
        assertThat(first.venues()).allSatisfy(v -> {
            assertThat(v.isDemoPool()).isTrue();
            assertThat(v.isPublicSafeSpot()).isTrue();
            // Everything is clustered within ~3 km of the caller.
            assertThat(PartyMatchService.haversineKm(41.8781, -87.6298, v.getLat(), v.getLng()))
                    .isLessThan(3.0);
        });

        GpsSeedResponse repeat = service.gpsSeed(new GpsSeedRequest(41.8781, -87.6298, "Chicago"));
        assertThat(repeat.venuesCreated()).isZero();
        assertThat(repeat.venues()).hasSize(7);
    }

    // ------------------------------------------------------ 3 · slots & queue

    @Test
    void venueSlotsListsOnlyOpenSlotsAndRecomputesLoad() {
        venue("v1", "Snakes & Lattes", "Chicago", 20, 41.90, -87.67, true);
        party("pOpen", "Chicago", "v1", "Snakes & Lattes", 4, 1, 10, 30,
                GuestSession.SOLO, GuestSession.CHILL, List.of(), false);
        Party done = party("pDone", "Chicago", "v1", "Snakes & Lattes", 4, 4, 10, 30,
                GuestSession.SOLO, GuestSession.CHILL, List.of(), false);
        done.setStatus(Party.COMPLETED);

        VenueSlotsResponse response = service.venueSlots("v1");

        assertThat(response.venue().getId()).isEqualTo("v1");
        assertThat(response.slots()).extracting(Party::getId).containsExactly("pOpen");
        assertThat(response.activeSlotCount()).isEqualTo(1);

        assertThatThrownBy(() -> service.venueSlots("nope"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(status((ResponseStatusException) ex)).isEqualTo(404));
    }

    @Test
    void queueOnlyAddsGuestsOnceTheSlotIsAtCapacity() {
        venue("v1", "Spot", "Chicago", 20, 41.88, -87.63, true);
        Party open = party("pOpen", "Chicago", "v1", "Spot", 4, 1, 10, 30,
                GuestSession.SOLO, GuestSession.CHILL, List.of(), false);
        Party full = party("pFull", "Chicago", "v1", "Spot", 2, 2, 10, 30,
                GuestSession.SOLO, GuestSession.CHILL, List.of(), false);

        service.queueForSlot(open.getId(), new QueueRequest("g1", "Quinn Alvarez"));
        assertThat(open.getQueueGuestNames()).isEmpty();

        service.queueForSlot(full.getId(), new QueueRequest("g1", "Quinn Alvarez"));
        assertThat(full.getQueueGuestNames()).containsExactly("Quinn Alvarez");
    }

    // ------------------------------------------- 4 · fair-midpoint calculation

    @Test
    void midpointPartyLocksTheClosestAffordablePublicSpotOnJoin() {
        guest("gA", "Host", "Chicago", 40.0, -80.0, 30);
        guest("gB", "Guest", "Chicago", 40.2, -80.2, 30);
        venue("vNear", "Near Public Spot", "Chicago", 20, 40.1, -80.1, true);
        venue("vFar", "Far Cheap Spot", "Chicago", 10, 41.0, -87.0, true);
        venue("vPricy", "Pricy Spot", "Chicago", 500, 40.1, -80.1, true);

        Party created = service.createParty(new CreatePartyRequest(
                "Midpoint Meetup", "Board Games", null, "Downtown", 4, 0, 30, "This weekend",
                null, List.of(), "gA", true, GuestSession.SOLO, GuestSession.CHILL, false));

        assertThat(created.isMidpointMode()).isTrue();
        assertThat(created.getVenueName()).isEqualTo("TBD • Auto-Midpoint Public Spot");

        Party joined = service.joinParty(created.getId(), "gB", null);

        // Average of (40.0,-80.0) and (40.2,-80.2) is closest to the near venue, which also fits the
        // party budget; the pricier spot is irrelevant because its cost exceeds budgetMax.
        assertThat(joined.getVenueName()).isEqualTo("Near Public Spot");
        assertThat(joined.getMemberGuestIds()).containsExactly("gA", "gB");
    }

    @Test
    void explicitVenuePartyUsesTheChosenVenueAndHaversineDistance() {
        guest("gA", "Host", "Chicago", 41.88, -87.63, 40);
        venue("v1", "Snakes & Lattes", "Chicago", 22, 41.9088, -87.6774, true);

        Party created = service.createParty(new CreatePartyRequest(
                "Catan Night", "Board Games", "Chicago", "Wicker Park", 4, 10, 30, "Sat 7 PM",
                "v1", List.of(), "gA", false, GuestSession.SOLO, GuestSession.CHILL, false));

        assertThat(created.isMidpointMode()).isFalse();
        assertThat(created.getVenueId()).isEqualTo("v1");

        PartyMatchService.SearchOutcome outcome = service.search("gA", "Chicago", null, null,
                -1, -1, "All", "All", false, false, false, false);
        Party scored = outcome.parties().stream().filter(p -> p.getId().equals(created.getId()))
                .findFirst().orElseThrow();
        assertThat(scored.getDistanceKm()).isGreaterThan(0);
    }

    // ------------------------------------------------------ 6 · free event radar

    @Test
    void radarFiltersByBudgetAndSortsCheapestFirst() {
        repository.saveRadarEvent(new RadarEvent("r1", "Free Museum Day", "Chicago",
                RadarEvent.FREE_MUSEUM, 0, "Sat 10 AM", "Field Museum", 41.86, -87.61, true));
        repository.saveRadarEvent(new RadarEvent("r2", "Street Fair", "Chicago",
                RadarEvent.LOCAL_FAIR, 5, "Sun 9 AM", "Maxwell Street", 41.86, -87.64, true));
        repository.saveRadarEvent(new RadarEvent("r3", "Gala", "Chicago",
                RadarEvent.LOCAL_FAIR, 50, "Sun 7 PM", "Downtown", 41.88, -87.63, true));

        List<RadarEvent> freeOnly = service.radar("Chicago", 0);
        List<RadarEvent> lowBudget = service.radar("Chicago", 10);
        List<RadarEvent> unlimited = service.radar("Chicago", -1);

        assertThat(freeOnly).extracting(RadarEvent::getCostPerPerson).containsExactly(0);
        assertThat(lowBudget).extracting(RadarEvent::getCostPerPerson).containsExactly(0, 5);
        assertThat(unlimited).extracting(RadarEvent::getCostPerPerson).containsExactly(0, 5, 50);
    }

    // ----------------------------------- 7 · friends, invites, check-in, profile

    @Test
    void friendRequestLifecycleIsGuarded() {
        guest("gA", "Ada", "Chicago", 41.88, -87.63, 40);
        guest("gB", "Ben", "Chicago", 41.88, -87.63, 40);

        FriendRelation pending = service.requestFriend("gA", "gB");
        assertThat(pending.getStatus()).isEqualTo(FriendRelation.PENDING);

        FriendRelation accepted = service.acceptFriendRequest(pending.getId());
        assertThat(accepted.getStatus()).isEqualTo(FriendRelation.ACCEPTED);

        assertThatThrownBy(() -> service.acceptFriendRequest(pending.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(status((ResponseStatusException) ex)).isEqualTo(409));

        assertThatThrownBy(() -> service.requestFriend("gA", "gA"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(status((ResponseStatusException) ex)).isEqualTo(400));
    }

    @Test
    void acceptingAnInviteDropsTheGuestIntoTheParty() {
        guest("gA", "Ada", "Chicago", 41.88, -87.63, 40);
        guest("gB", "Ben", "Chicago", 41.88, -87.63, 40);
        Party party = party("p1", "Chicago", null, "Spot", 4, 1, 10, 30,
                GuestSession.SOLO, GuestSession.CHILL, List.of("gA"), false);

        SlotInvite invite = service.createInvite("gA", "gB", party.getId());
        assertThat(invite.getStatus()).isEqualTo(SlotInvite.PENDING);

        SlotInvite accepted = service.respondToInvite(invite.getId(), "ACCEPT");
        assertThat(accepted.getStatus()).isEqualTo(SlotInvite.ACCEPTED);
        assertThat(party.getMemberGuestIds()).contains("gB");
    }

    @Test
    void respondToInviteRejectsAnUnknownAction() {
        guest("gA", "Ada", "Chicago", 41.88, -87.63, 40);
        guest("gB", "Ben", "Chicago", 41.88, -87.63, 40);
        Party party = party("p1", "Chicago", null, "Spot", 4, 1, 10, 30,
                GuestSession.SOLO, GuestSession.CHILL, List.of("gA"), false);
        SlotInvite invite = service.createInvite("gA", "gB", party.getId());

        assertThatThrownBy(() -> service.respondToInvite(invite.getId(), "MAYBE"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(status((ResponseStatusException) ex)).isEqualTo(400));
    }

    @Test
    void checkInRecordsEachGuestOnce() {
        guest("gA", "Ada", "Chicago", 41.88, -87.63, 40);
        Party party = party("p1", "Chicago", null, "Spot", 4, 1, 10, 30,
                GuestSession.SOLO, GuestSession.CHILL, List.of("gA"), false);

        service.checkIn(party.getId(), "gA");
        service.checkIn(party.getId(), "gA");

        assertThat(party.getCheckedInGuestIds()).containsExactly("gA");
    }

    @Test
    void profileUpdateIsPartialAndRejectsMissingGuest() {
        GuestSession session = guest("gA", "Old Name", "Chicago", 41.88, -87.63, 40);
        session.setBudgetMin(5);

        GuestSession updated = service.updateProfile(new ProfileUpdateRequest(
                "gA", "New Name", null, null, null, null, null, null, null, null, null, null));

        assertThat(updated.getDisplayName()).isEqualTo("New Name");
        assertThat(updated.getCity()).isEqualTo("Chicago");
        assertThat(updated.getBudgetMin()).isEqualTo(5);

        assertThatThrownBy(() -> service.updateProfile(new ProfileUpdateRequest(
                null, "X", null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(status((ResponseStatusException) ex)).isEqualTo(400));
    }

    // ----------------------------------------------------- create / join guards

    @Test
    void createPartyValidatesInputAndGeneratesIcebreakers() {
        guest("gA", "Host", "Chicago", 41.88, -87.63, 40);
        venue("v1", "Snakes & Lattes", "Chicago", 22, 41.9088, -87.6774, true);

        assertThatThrownBy(() -> service.createParty(new CreatePartyRequest(
                "  ", "Board Games", "Chicago", "Wicker Park", 4, 10, 30, "Sat",
                "v1", List.of(), "gA", false, GuestSession.SOLO, GuestSession.CHILL, false)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(status((ResponseStatusException) ex)).isEqualTo(400));

        assertThatThrownBy(() -> service.createParty(new CreatePartyRequest(
                "No Venue", "Board Games", "Chicago", "Wicker Park", 4, 10, 30, "Sat",
                null, List.of(), "gA", false, GuestSession.SOLO, GuestSession.CHILL, false)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(status((ResponseStatusException) ex)).isEqualTo(400));

        Party created = service.createParty(new CreatePartyRequest(
                "Catan Night", "Board Games", "Chicago", "Wicker Park", 4, 10, 30, "Sat 7 PM",
                "v1", List.of(), "gA", false, GuestSession.SOLO, GuestSession.CHILL, false));

        assertThat(created.getStatus()).isEqualTo(Party.OPEN);
        assertThat(created.getIcebreakers()).hasSize(3);
        assertThat(created.getMemberNames()).contains("Host");
    }

    @Test
    void joinPartyRejectsFullAndCompletedParties() {
        guest("gA", "Ada", "Chicago", 41.88, -87.63, 40);
        guest("gX", "Xena", "Chicago", 41.88, -87.63, 40);
        Party full = party("pFull", "Chicago", null, "Spot", 2, 2, 10, 30,
                GuestSession.SOLO, GuestSession.CHILL, List.of("gA"), false);

        assertThatThrownBy(() -> service.joinParty(full.getId(), "gX", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(status((ResponseStatusException) ex)).isEqualTo(409));

        Party done = party("pDone", "Chicago", null, "Spot", 4, 1, 10, 30,
                GuestSession.SOLO, GuestSession.CHILL, List.of(), false);
        done.setStatus(Party.COMPLETED);

        assertThatThrownBy(() -> service.joinParty(done.getId(), "gX", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(status((ResponseStatusException) ex)).isEqualTo(409));
    }

    @Test
    void completePartyAndRateVisitRecalculateTheVenueAverage() {
        Venue spot = new Venue("v1", "Spot", "Board Game Cafe", "Chicago", "Downtown",
                20, 4.0, 10, List.of("Great Table Space"), 41.88, -87.63);
        repository.saveVenue(spot);

        Party party = party("p1", "Chicago", "v1", "Spot", 4, 2, 10, 30,
                GuestSession.SOLO, GuestSession.CHILL, List.of(), false);

        VisitRecord record = service.completeParty(party.getId());
        assertThat(party.getStatus()).isEqualTo(Party.COMPLETED);
        assertThat(record.getRating()).isNull();

        service.rateVisit(record.getId(), 5, List.of("Great Wi-Fi"), "Loved it");
        assertThat(spot.getRatingCount()).isEqualTo(11);
        assertThat(spot.getAvgRating()).isEqualTo(4.1); // (40 + 5) / 11 rounded to 1 decimal

        // Re-rating replaces the previous star without double-counting.
        service.rateVisit(record.getId(), 3, List.of(), "Second thoughts");
        assertThat(spot.getRatingCount()).isEqualTo(11);
        assertThat(spot.getAvgRating()).isEqualTo(3.9); // (45 - 5 + 3) / 11

        assertThatThrownBy(() -> service.rateVisit(record.getId(), 9, List.of(), ""))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(status((ResponseStatusException) ex)).isEqualTo(400));
    }
}
