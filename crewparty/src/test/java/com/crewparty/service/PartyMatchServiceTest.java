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
import java.util.Map;

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

    /** Upgrades a test guest to CrewParty Pro so the free-tier limits stop applying. */
    private void pro(String guestId) {
        GuestSession session = repository.findGuest(guestId).orElseThrow();
        session.setPro(true);
        repository.saveGuest(session);
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
        // A couple of templates are promoted partners so any GPS city shows ads even zoomed out.
        assertThat(first.venues()).filteredOn(Venue::isFeaturedPartner).hasSize(3);

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
        pro("gA"); // Fair-Midpoint is a Pro feature, so the host must be upgraded.
        venue("vNear", "Near Public Spot", "Chicago", 20, 40.1, -80.1, true);
        venue("vFar", "Far Cheap Spot", "Chicago", 10, 41.0, -87.0, true);
        venue("vPricy", "Pricy Spot", "Chicago", 500, 40.1, -80.1, true);

        Party created = service.createParty(new CreatePartyRequest(
                "Midpoint Meetup", "Board Games", null, "Downtown", 4, 0, 30, "This weekend",
                null, List.of(), "gA", true, GuestSession.SOLO, GuestSession.CHILL, false,
                null, null, null, null, false));

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
                "v1", List.of(), "gA", false, GuestSession.SOLO, GuestSession.CHILL, false,
                null, null, null, null, false));

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
                "gA", "New Name", null, null, null, null, null, null, null, null, null, null, null, null));

        assertThat(updated.getDisplayName()).isEqualTo("New Name");
        assertThat(updated.getCity()).isEqualTo("Chicago");
        assertThat(updated.getBudgetMin()).isEqualTo(5);

        assertThatThrownBy(() -> service.updateProfile(new ProfileUpdateRequest(
                null, "X", null, null, null, null, null, null, null, null, null, null, null, null)))
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
                "v1", List.of(), "gA", false, GuestSession.SOLO, GuestSession.CHILL, false,
                null, null, null, null, false)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(status((ResponseStatusException) ex)).isEqualTo(400));

        assertThatThrownBy(() -> service.createParty(new CreatePartyRequest(
                "No Venue", "Board Games", "Chicago", "Wicker Park", 4, 10, 30, "Sat",
                null, List.of(), "gA", false, GuestSession.SOLO, GuestSession.CHILL, false,
                null, null, null, null, false)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(status((ResponseStatusException) ex)).isEqualTo(400));

        Party created = service.createParty(new CreatePartyRequest(
                "Catan Night", "Board Games", "Chicago", "Wicker Park", 4, 10, 30, "Sat 7 PM",
                "v1", List.of(), "gA", false, GuestSession.SOLO, GuestSession.CHILL, false,
                null, null, null, null, false));

        assertThat(created.getStatus()).isEqualTo(Party.OPEN);
        assertThat(created.getIcebreakers()).hasSize(3);
        assertThat(created.getMemberNames()).contains("Host");
    }

    @Test
    void listedPlacePartyBindsToOrCreatesAVenueAndStaysSafetyVerified() {
        guest("gA", "Host", "Chicago", 41.88, -87.63, 40);
        pro("gA"); // Two active parties are Pro-only, so this host upgrades first.

        Party created = service.createParty(new CreatePartyRequest(
                "Park Frisbee", "Outdoor Casual", "Chicago", "Lincoln Park", 4, 0, 20, "Sun 11 AM",
                null, List.of(), "gA", false, GuestSession.SOLO, GuestSession.CHILL, false,
                "Lincoln Park Cafe", "Cafe", 41.9214, -87.6330, true));

        assertThat(created.isMidpointMode()).isFalse();
        assertThat(created.getVenueId()).isNotNull();
        assertThat(created.getVenueName()).isEqualTo("Lincoln Park Cafe");

        Venue venue = repository.findVenue(created.getVenueId()).orElseThrow();
        assertThat(venue.getName()).isEqualTo("Lincoln Park Cafe");
        assertThat(venue.getCategory()).isEqualTo("Cafe");
        assertThat(venue.isPublicSafeSpot()).isTrue();
        assertThat(venue.isFeaturedPartner()).isFalse();

        // A second tap on the same real place reuses the venue instead of creating a duplicate.
        Party again = service.createParty(new CreatePartyRequest(
                "Park Frisbee Again", "Outdoor Casual", "Chicago", "Lincoln Park", 4, 0, 20, "Sun 2 PM",
                null, List.of(), "gA", false, GuestSession.SOLO, GuestSession.CHILL, false,
                "Lincoln Park Cafe", "Cafe", 41.9214, -87.6330, true));
        assertThat(again.getVenueId()).isEqualTo(created.getVenueId());
    }

    @Test
    void customSpotPartyKeepsNoVenueAndIsHiddenByThePublicSafeFilter() {
        guest("gA", "Host", "Chicago", 41.88, -87.63, 40);

        Party custom = service.createParty(new CreatePartyRequest(
                "Courtyard Jam", "Outdoor Casual", "Chicago", "Logan Square", 4, 0, 20, "Sat 4 PM",
                null, List.of(), "gA", false, GuestSession.SOLO, GuestSession.CHILL, false,
                "Maple Street Courtyard", null, 41.9200, -87.7000, false));

        assertThat(custom.getVenueId()).isNull();
        assertThat(custom.getVenueName()).isEqualTo("Maple Street Courtyard");
        assertThat(custom.isMidpointMode()).isFalse();
        assertThat(custom.getLat()).isEqualTo(41.9200);
        assertThat(custom.getLng()).isEqualTo(-87.7000);

        // Off: the custom spot shows up in the open list.
        PartyMatchService.SearchOutcome open = service.search("gA", "Chicago", null, null,
                -1, -1, "All", "All", false, false, false, false);
        assertThat(open.parties()).extracting(Party::getId).contains(custom.getId());

        // On: a custom spot is not a verified Public Safe Spot, so the safety filter drops it.
        PartyMatchService.SearchOutcome safe = service.search("gA", "Chicago", null, null,
                -1, -1, "All", "All", false, true, false, false);
        assertThat(safe.parties()).extracting(Party::getId).doesNotContain(custom.getId());
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

    // ------------------------------------------------- leaving a slot

    @Test
    void leavingFreesTheSeatAndKeepsThePartyOpenWhileOthersStay() {
        guest("gHost", "Host", "Chicago", 41.88, -87.63, 40);
        guest("gMember", "Member", "Chicago", 41.88, -87.63, 40);
        Venue spot = venue("v1", "Snakes & Lattes", "Chicago", 22, 41.9088, -87.6774, true);
        Party open = party("p1", "Chicago", "v1", "Snakes & Lattes", 4, 2,
                10, 30, GuestSession.SOLO, GuestSession.CHILL, List.of("gHost", "gMember"), false);

        Party after = service.leaveParty(open.getId(), "gMember");

        assertThat(after.getStatus()).isEqualTo(Party.OPEN);
        assertThat(after.getMemberGuestIds()).containsExactly("gHost");
        assertThat(after.getFilledSlots()).isEqualTo(1);
        assertThat(spot.getActiveSlotCount()).isEqualTo(1);
    }

    @Test
    void hostLeavingAnEmptyPartyCompletesItAndDropsTheVenueLoad() {
        guest("gHost", "Host", "Chicago", 41.88, -87.63, 40);
        Venue spot = venue("v1", "Snakes & Lattes", "Chicago", 22, 41.9088, -87.6774, true);
        Party solo = party("p1", "Chicago", "v1", "Snakes & Lattes", 4, 1,
                10, 30, GuestSession.SOLO, GuestSession.CHILL, List.of("gHost"), false);

        Party after = service.leaveParty(solo.getId(), "gHost");

        assertThat(after.getStatus()).isEqualTo(Party.COMPLETED);
        assertThat(after.getMemberGuestIds()).isEmpty();
        assertThat(after.getFilledSlots()).isZero();
        assertThat(spot.getActiveSlotCount()).isZero();
    }

    @Test
    void leavePartyRejectsNonMembersAndUnknownParties() {
        guest("gHost", "Host", "Chicago", 41.88, -87.63, 40);
        guest("gOutsider", "Outsider", "Chicago", 41.88, -87.63, 40);
        Party open = party("p1", "Chicago", null, "Spot", 4, 1, 10, 30,
                GuestSession.SOLO, GuestSession.CHILL, List.of("gHost"), false);

        assertThatThrownBy(() -> service.leaveParty(open.getId(), "gOutsider"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(status((ResponseStatusException) ex)).isEqualTo(409));

        assertThatThrownBy(() -> service.leaveParty("missing", "gHost"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(status((ResponseStatusException) ex)).isEqualTo(404));
    }

    @Test
    void profileUpdateStoresLiveCoordinates() {
        guest("gGps", "Nomad", "Chicago", 41.88, -87.63, 40);

        service.updateProfile(new ProfileUpdateRequest("gGps", null, null, "Астана", null,
                null, null, null, null, null, null, null, 51.1694, 71.4491));

        GuestSession session = repository.findGuest("gGps").orElseThrow();
        assertThat(session.getCity()).isEqualTo("Астана");
        assertThat(session.getLat()).isEqualTo(51.1694);
        assertThat(session.getLng()).isEqualTo(71.4491);
    }

    // ------------------------------------------------- free tier & Pro perks

    @Test
    void freePlanCapsActivePartiesAndBlocksMidpoint() {
        guest("gFree", "Free Host", "Chicago", 41.88, -87.63, 40);
        venue("v1", "Snakes & Lattes", "Chicago", 22, 41.9088, -87.6774, true);

        service.createParty(new CreatePartyRequest(
                "First Party", "Board Games", "Chicago", "Wicker Park", 4, 10, 30, "Sat 7 PM",
                "v1", List.of(), "gFree", false, GuestSession.SOLO, GuestSession.CHILL, false,
                null, null, null, null, false));

        assertThatThrownBy(() -> service.createParty(new CreatePartyRequest(
                "Second Party", "Board Games", "Chicago", "Wicker Park", 4, 10, 30, "Sat 9 PM",
                "v1", List.of(), "gFree", false, GuestSession.SOLO, GuestSession.CHILL, false,
                null, null, null, null, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("max 1 active party")
                .satisfies(ex -> assertThat(status((ResponseStatusException) ex)).isEqualTo(402));

        guest("gMid", "Mid Host", "Chicago", 41.88, -87.63, 40);
        assertThatThrownBy(() -> service.createParty(new CreatePartyRequest(
                "Midpoint Meetup", "Board Games", "Chicago", "Downtown", 4, 0, 30, "This weekend",
                null, List.of(), "gMid", true, GuestSession.SOLO, GuestSession.CHILL, false,
                null, null, null, null, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Fair-Midpoint")
                .satisfies(ex -> assertThat(status((ResponseStatusException) ex)).isEqualTo(402));
    }

    @Test
    void freePlanCapsActiveHangoutsUntilProUpgrades() {
        guest("gFree", "Free Guest", "Chicago", 41.88, -87.63, 40);
        party("p1", "Chicago", null, "Spot", 4, 1, 10, 30,
                GuestSession.SOLO, GuestSession.CHILL, List.of("gFree"), false);
        party("p2", "Chicago", null, "Spot", 4, 1, 10, 30,
                GuestSession.SOLO, GuestSession.CHILL, List.of("gFree"), false);
        Party third = party("p3", "Chicago", null, "Spot", 4, 1, 10, 30,
                GuestSession.SOLO, GuestSession.CHILL, List.of(), false);

        assertThatThrownBy(() -> service.joinParty(third.getId(), "gFree", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("max 2 active hangouts")
                .satisfies(ex -> assertThat(status((ResponseStatusException) ex)).isEqualTo(402));

        pro("gFree");
        assertThat(service.joinParty(third.getId(), "gFree", null).getMemberGuestIds()).contains("gFree");
    }

    @Test
    void checkoutUpgradesTheGuestAndProPerksApply() {
        guest("gA", "Host", "Chicago", 41.88, -87.63, 40);

        Map<String, Object> response = service.checkout(Map.of("guestId", "gA"));
        assertThat(response).containsEntry("status", "PRO_UNLOCKED")
                .containsEntry("plan", "CrewParty Pro")
                .containsEntry("amountUsd", 9.99);
        assertThat(repository.findGuest("gA").orElseThrow().isPro()).isTrue();

        // Omitting the body keeps the legacy response shape.
        assertThat(service.checkout(null)).containsEntry("status", "PRO_UNLOCKED");

        venue("v1", "Snakes & Lattes", "Chicago", 22, 41.9088, -87.6774, true);
        Party hosted = service.createParty(new CreatePartyRequest(
                "Pro Night", "Board Games", "Chicago", "Wicker Park", 4, 10, 30, "Sat 7 PM",
                "v1", List.of(), "gA", false, GuestSession.SOLO, GuestSession.CHILL, false,
                null, null, null, null, false));
        assertThat(hosted.getTrustBadges()).contains("Pro Host");
    }

    @Test
    void proMembersCutAheadInTheSlotQueue() {
        guest("gFree", "Free Guest", "Chicago", 41.88, -87.63, 40);
        guest("gPro", "Pro Guest", "Chicago", 41.88, -87.63, 40);
        pro("gPro");
        Party full = party("pFull", "Chicago", null, "Spot", 2, 2, 10, 30,
                GuestSession.SOLO, GuestSession.CHILL, List.of(), false);

        service.queueForSlot(full.getId(), new QueueRequest("gFree", "Free Guest"));
        service.queueForSlot(full.getId(), new QueueRequest("gPro", "Pro Guest"));

        assertThat(full.getQueueGuestNames()).containsExactly("Pro Guest", "Free Guest");
    }

    // --------------------------------------------- live map / slot synchronisation

    @Test
    void presenceReportsTheSharedStateRevision() {
        GuestSession me = guest("gA", "Ada", "Chicago", 41.88, -87.63, 40);

        PresenceResponse presence = service.presence(me.getGuestId());

        assertThat(presence.stateRevision()).isEqualTo(repository.getStateRevision());
    }

    @Test
    void everySharedChangeBumpsTheStateRevision() {
        guest("gA", "Ada", "Chicago", 41.88, -87.63, 40);
        guest("gB", "Bob", "Chicago", 41.88, -87.63, 40);

        long before = repository.getStateRevision();

        venue("v1", "Snakes & Lattes", "Chicago", 22, 41.9088, -87.6774, true);
        long afterVenue = repository.getStateRevision();
        assertThat(afterVenue).isGreaterThan(before);

        Party open = party("p1", "Chicago", "v1", "Snakes & Lattes", 4, 2, 10, 30,
                GuestSession.SOLO, GuestSession.CHILL, List.of("gA"), false);
        long afterParty = repository.getStateRevision();
        assertThat(afterParty).isGreaterThan(afterVenue);

        // Joining, queueing, leaving and completing all recalculate the venue load, which bumps too.
        service.joinParty(open.getId(), "gB", null);
        long afterJoin = repository.getStateRevision();
        assertThat(afterJoin).isGreaterThan(afterParty);

        Party full = party("pFull", "Chicago", "v1", "Snakes & Lattes", 2, 2, 10, 30,
                GuestSession.SOLO, GuestSession.CHILL, List.of("gA", "gB"), false);
        long beforeQueue = repository.getStateRevision();
        service.queueForSlot(full.getId(), new QueueRequest("gA", "Ada"));
        long afterQueue = repository.getStateRevision();
        assertThat(afterQueue).isGreaterThan(beforeQueue);

        service.leaveParty(open.getId(), "gB");
        assertThat(repository.getStateRevision()).isGreaterThan(afterQueue);

        long beforeComplete = repository.getStateRevision();
        service.completeParty(open.getId());
        assertThat(repository.getStateRevision()).isGreaterThan(beforeComplete);
    }

    @Test
    void aLowFitCustomSpotStaysVisibleToEveryoneInTown() {
        guest("gHost", "Host", "Chicago", 41.88, -87.63, 40);
        guest("gOther", "Other", "Chicago", 41.88, -87.63, 40);

        Party custom = service.createParty(new CreatePartyRequest(
                "Rooftop Hang", "Outdoor Casual", "Chicago", "Logan Square", 4, 0, 20, "Sat 4 PM",
                null, List.of(), "gHost", false, GuestSession.SOLO, GuestSession.CHILL, false,
                "Maple Street Courtyard", null, 41.9200, -87.7000, false));

        // Searching another theme leaves the custom spot under the fit-score cut-off…
        PartyMatchService.SearchOutcome other = service.search("gOther", "Chicago", "Board Games", null,
                -1, -1, "All", "All", false, false, false, false);
        assertThat(custom.getFitScore()).isLessThan(65);

        // …yet a real map spot with a name is never hidden from other users in the same city.
        assertThat(other.parties()).extracting(Party::getId).contains(custom.getId());
    }
}
