package com.crewparty.repository;

import com.crewparty.model.Friend;
import com.crewparty.model.FriendRelation;
import com.crewparty.model.GuestSession;
import com.crewparty.model.Party;
import com.crewparty.model.RadarEvent;
import com.crewparty.model.SlotInvite;
import com.crewparty.model.Venue;
import com.crewparty.model.VisitRecord;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe, in-memory store seeded with realistic US demo data.
 * Keeps its insertion order so the demo always renders the same way, and returns defensive copies
 * on read so several open browser tabs can hammer it at once without corrupting state.
 */
@Repository
public class InMemoryStoreRepository {

    private final List<Friend> friends = new CopyOnWriteArrayList<>();
    private final List<Venue> venues = new CopyOnWriteArrayList<>();
    private final List<Party> parties = new CopyOnWriteArrayList<>();
    private final List<VisitRecord> visitHistory = new CopyOnWriteArrayList<>();

    /** Login-free tab identities, keyed by guestId, so presence lookups stay O(1). */
    private final Map<String, GuestSession> guestSessions = new ConcurrentHashMap<>();
    private final List<FriendRelation> friendRelations = new CopyOnWriteArrayList<>();
    private final List<SlotInvite> slotInvites = new CopyOnWriteArrayList<>();
    private final List<RadarEvent> radarEvents = new CopyOnWriteArrayList<>();

    private final AtomicInteger partySequence = new AtomicInteger(3);
    private final AtomicInteger visitSequence = new AtomicInteger(2);
    private final AtomicInteger venueSequence = new AtomicInteger(8);
    private final AtomicInteger guestSequence = new AtomicInteger(0);
    private final AtomicInteger relationSequence = new AtomicInteger(0);
    private final AtomicInteger inviteSequence = new AtomicInteger(0);
    private final AtomicInteger radarSequence = new AtomicInteger(0);
    private final AtomicInteger personaIndex = new AtomicInteger(0);

    /**
     * Monotonic revision of the shared party/venue data. Every open tab polls {@code GET /api/presence}
     * and reloads itself only when this number moves, which is what keeps the map and the open slots
     * in sync across users without any websocket.
     */
    private final AtomicLong stateRevision = new AtomicLong(1);

    /**
     * The ambient "who else is in town" crowd. Their heartbeat is refreshed instead of being aged out,
     * so even a single-tab demo shows a living presence list.
     */
    private static final Set<String> AMBIENT_GUEST_IDS = Set.of("gs1", "gs2", "gs3");

    /** Rotating US 25-45 personas: two fresh tabs therefore never get the same identity. */
    private static final List<GuestPersona> PERSONAS = List.of(
            new GuestPersona("Jordan Vance", "28 • UX Designer", "Chicago", "Logan Square",
                    41.9276, -87.7103, GuestSession.SOLO, GuestSession.CHILL, 15, 40,
                    List.of("ID Verified", "Shared Interests")),
            new GuestPersona("Taylor Brooks", "33 • Product Manager", "Austin", "East Austin",
                    30.2586, -97.7100, GuestSession.COUPLES, GuestSession.ACTIVE, 20, 55,
                    List.of("ID Verified", "LinkedIn")),
            new GuestPersona("Casey Morgan", "31 • Data Scientist", "Seattle", "Fremont",
                    47.6505, -122.3509, GuestSession.SOLO, GuestSession.CHILL, 10, 35,
                    List.of("LinkedIn")),
            new GuestPersona("Riley Chen", "27 • Graphic Designer", "Washington, D.C.", "Shaw",
                    38.9120, -77.0219, GuestSession.SOLO, GuestSession.ACTIVE, 15, 45,
                    List.of("ID Verified")),
            new GuestPersona("Avery Thompson", "35 • Sales Lead", "Chicago", "River North",
                    41.8925, -87.6341, GuestSession.COUPLES, GuestSession.CHILL, 25, 60,
                    List.of("ID Verified", "LinkedIn", "Shared Interests")),
            new GuestPersona("Quinn Alvarez", "30 • High School Teacher", "Austin", "South Congress",
                    30.2494, -97.7500, GuestSession.FAMILY, GuestSession.CHILL, 10, 30,
                    List.of("Shared Interests")),
            new GuestPersona("Morgan Fitzpatrick", "41 • Architect", "Seattle", "Queen Anne",
                    47.6372, -122.3570, GuestSession.SOLO, GuestSession.ACTIVE, 20, 50,
                    List.of("ID Verified")),
            new GuestPersona("Jamie Osei", "26 • ER Nurse", "Washington, D.C.", "Navy Yard",
                    38.8765, -77.0040, GuestSession.FAMILY, GuestSession.ACTIVE, 15, 40,
                    List.of("ID Verified", "LinkedIn")));

    @PostConstruct
    void seedDemoData() {
        seedVenues();
        seedFriends();
        seedParties();
        seedVisitHistory();
        seedRadarEvents();
        seedAmbientGuests();
        recountVenueLoad();
    }

    // ------------------------------------------------------------------ seeds

    private void seedVenues() {
        venues.add(venue(new Venue("v1", "Snakes & Lattes Chicago", "Board Game Cafe", "Chicago", "Wicker Park",
                22, 4.7, 312, List.of("Great Table Space", "Budget Friendly", "Late Night"),
                41.9088, -87.6774), true));
        venues.add(venue(new Venue("v2", "Vigilante Gastropub & Games", "Gastropub & Games", "Austin", "North Loop",
                28, 4.8, 241, List.of("Great Table Space", "Craft Drinks", "Late Night"),
                30.3165, -97.7270), false));
        venues.add(venue(new Venue("v3", "Mox Boarding House Seattle", "Board Game Cafe", "Seattle", "Ballard",
                25, 4.6, 187, List.of("Great Table Space", "Huge Game Library", "Budget Friendly"),
                47.6685, -122.3845), true));
        venues.add(venue(new Venue("v4", "The Board Room DC", "Board Game Bar", "Washington, D.C.", "Dupont Circle",
                26, 4.5, 154, List.of("Craft Drinks", "Quiet Enough to Talk", "Great Table Space"),
                38.9096, -77.0434), true));
        venues.add(venue(new Venue("v5", "Ignite Gaming Lounge", "Esports & Gaming Lounge", "Chicago", "Avondale",
                30, 4.4, 96, List.of("Great Wi-Fi", "Late Night", "Co-op Video Games"),
                41.9389, -87.7111), false));
        venues.add(venue(new Venue("v6", "Emerald Tavern Games & Cafe", "Board Game Cafe", "Austin", "North Austin",
                18, 4.7, 203, List.of("Budget Friendly", "Quiet Enough to Talk", "Great Table Space"),
                30.3835, -97.7298), true));
        venues.add(venue(new Venue("v7", "Capitol CoWork & Play", "Coworking Lounge", "Washington, D.C.", "NoMa",
                20, 4.3, 88, List.of("Great Wi-Fi", "Quiet Enough to Talk", "Budget Friendly"),
                38.9067, -77.0031), true));
        venues.add(venue(new Venue("v8", "Raygun Lounge", "Gaming Bar & Cafe", "Seattle", "Capitol Hill",
                15, 4.4, 121, List.of("Budget Friendly", "Quiet Enough to Talk", "Craft Drinks"),
                47.6145, -122.3185), false));
    }

    private Venue venue(Venue venue, boolean featuredPartner) {
        venue.setPublicSafeSpot(true);
        venue.setFeaturedPartner(featuredPartner);
        venue.setDemoPool(false);
        return venue;
    }

    private void seedFriends() {
        friends.add(new Friend("f1", "Alex Rivera", "29 • Product Designer", "Chicago",
                List.of("Board Games", "Co-op Video Games"), Friend.CORE_BUDDY, true));
        friends.add(new Friend("f2", "Priya Natarajan", "27 • UX Researcher", "Chicago",
                List.of("Board Games", "Trivia & Social"), Friend.DISCOVER, true));
        friends.add(new Friend("f3", "Marcus Chen", "33 • Software Engineer", "Austin",
                List.of("Co-op Video Games", "Board Games"), Friend.DISCOVER, true));
        friends.add(new Friend("f4", "Sofia Delgado", "31 • Marketing Manager", "Austin",
                List.of("Trivia & Social", "Coworking & Chill"), Friend.DISCOVER, true));
        friends.add(new Friend("f5", "Ethan Brooks", "26 • Data Analyst", "Seattle",
                List.of("Board Games", "Outdoor Casual"), Friend.DISCOVER, false));
        friends.add(new Friend("f6", "Naomi Okafor", "38 • Product Manager", "Washington, D.C.",
                List.of("Coworking & Chill", "Trivia & Social"), Friend.DISCOVER, true));
        friends.add(new Friend("f7", "Liam Donnelly", "42 • Architect", "Seattle",
                List.of("Board Games", "Outdoor Casual"), Friend.DISCOVER, false));
    }

    private void seedParties() {
        Party catan = new Party("p1", "2 Buddies looking for 2 more for Catan & Craft Coffee",
                "Board Games", "Chicago", "Wicker Park", 4, 2, 10, 30, "Sat 3:00 PM",
                "v1", "Snakes & Lattes Chicago",
                List.of("You (Host)", "Alex Rivera"),
                List.of(
                        "What is the most chaotic Catan trade you have ever accepted?",
                        "House rule: whoever rolls a 7 buys the next round of coffee.",
                        "If you could add one resource tile to Catan, what would it be?"),
                Party.OPEN);
        catan.setFormat(GuestSession.SOLO);
        catan.setPace(GuestSession.CHILL);
        catan.setMiniGroupPriority(true);
        catan.setTrustBadges(List.of("ID Verified", "Shared Interests"));
        catan.setLat(41.9088);
        catan.setLng(-87.6774);
        parties.add(catan);

        Party coop = new Party("p2", "Co-op Night: Helldivers, Tacos & Craft Beer",
                "Co-op Video Games", "Austin", "North Loop", 3, 1, 15, 35, "Fri 7:00 PM",
                "v2", "Vigilante Gastropub & Games",
                List.of("Marcus Chen"),
                List.of(
                        "What is your go-to co-op loadout when the squad is short one player?",
                        "Friendly rule: no rage-quitting before the taco break.",
                        "Rank the three best couch co-op games of the last decade."),
                Party.OPEN);
        coop.setFormat(GuestSession.COUPLES);
        coop.setPace(GuestSession.ACTIVE);
        coop.setTrustBadges(List.of("ID Verified"));
        coop.setLat(30.3165);
        coop.setLng(-97.7270);
        parties.add(coop);

        Party trivia = new Party("p3", "Trivia Squad needs one ringer on Thursday",
                "Trivia & Social", "Washington, D.C.", "Dupont Circle", 4, 3, 0, 25, "Thu 8:00 PM",
                "v4", "The Board Room DC",
                List.of("Naomi Okafor", "Priya Natarajan", "You"),
                List.of(
                        "Which category would you bid everything on: music, sports or 90s TV?",
                        "Team rule: the loudest answer is never automatically the right one.",
                        "What is the most useless fact you still remember from school?"),
                Party.OPEN);
        trivia.setFormat(GuestSession.SOLO);
        trivia.setPace(GuestSession.CHILL);
        trivia.setTrustBadges(List.of("ID Verified", "LinkedIn"));
        trivia.setLat(38.9096);
        trivia.setLng(-77.0434);
        parties.add(trivia);
    }

    private void seedVisitHistory() {
        visitHistory.add(new VisitRecord("h1", "p0", "Friday Board Game Night at Snakes & Lattes",
                "Board Games", "v1", "Snakes & Lattes Chicago", "2026-02-06",
                List.of("You", "Alex Rivera", "Priya Natarajan"),
                5, List.of("Great Table Space", "Budget Friendly"),
                "Great table space and the staff actually taught us the Catan rules."));

        visitHistory.add(new VisitRecord("h2", "p0", "Coworking & Chill at Capitol CoWork",
                "Coworking & Chill", "v7", "Capitol CoWork & Play", "2026-02-13",
                List.of("You", "Alex Rivera", "Naomi Okafor"),
                null, List.of(), ""));
    }

    /** Curated weekend free / low-budget events per city. */
    private void seedRadarEvents() {
        radarEvents.add(radar("Free Museum Day at the Field Museum", "Chicago", RadarEvent.FREE_MUSEUM, 0,
                "Sat 10:00 AM", "Field Museum", 41.8663, -87.6170));
        radarEvents.add(radar("Maxwell Street Market Walk", "Chicago", RadarEvent.LOCAL_FAIR, 0,
                "Sun 9:00 AM", "Maxwell Street Market", 41.8665, -87.6433));
        radarEvents.add(radar("Lakefront Trail Meetup", "Chicago", RadarEvent.PARK_MEETUP, 0,
                "Sat 8:00 AM", "Grant Park", 41.8756, -87.6244));

        radarEvents.add(radar("Blanton Museum Free Day", "Austin", RadarEvent.FREE_MUSEUM, 0,
                "Sun 12:00 PM", "Blanton Museum of Art", 30.2807, -97.7376));
        radarEvents.add(radar("South Austin Flea Market", "Austin", RadarEvent.LOCAL_FAIR, 5,
                "Sat 11:00 AM", "The Far Out Lounge", 30.2116, -97.7793));
        radarEvents.add(radar("Zilker Park Frisbee Meetup", "Austin", RadarEvent.PARK_MEETUP, 0,
                "Sat 5:00 PM", "Zilker Metropolitan Park", 30.2669, -97.7729));

        radarEvents.add(radar("Free First Thursday at SAM", "Seattle", RadarEvent.FREE_MUSEUM, 0,
                "Thu 5:00 PM", "Seattle Art Museum", 47.6073, -122.3367));
        radarEvents.add(radar("Fremont Sunday Market", "Seattle", RadarEvent.LOCAL_FAIR, 0,
                "Sun 10:00 AM", "Fremont Sunday Market", 47.6512, -122.3508));
        radarEvents.add(radar("Green Lake Walk & Talk", "Seattle", RadarEvent.PARK_MEETUP, 0,
                "Sat 9:00 AM", "Green Lake Park", 47.6806, -122.3286));

        radarEvents.add(radar("Free Entry at the Smithsonian American Art Museum", "Washington, D.C.",
                RadarEvent.FREE_MUSEUM, 0, "Sat 11:00 AM", "Smithsonian American Art Museum",
                38.8979, -77.0230));
        radarEvents.add(radar("Eastern Market Weekend Fair", "Washington, D.C.", RadarEvent.LOCAL_FAIR, 0,
                "Sun 9:00 AM", "Eastern Market", 38.8869, -76.9961));
        radarEvents.add(radar("National Mall Sunset Meetup", "Washington, D.C.", RadarEvent.PARK_MEETUP, 0,
                "Sat 6:00 PM", "National Mall", 38.8893, -77.0230));
    }

    private RadarEvent radar(String title, String city, String category, int costPerPerson,
                             String whenLabel, String venueName, double lat, double lng) {
        return new RadarEvent("r" + radarSequence.incrementAndGet(), title, city, category,
                costPerPerson, whenLabel, venueName, lat, lng, true);
    }

    /** Three always-on ambient guests so presence is never empty in a single-tab demo. */
    private void seedAmbientGuests() {
        long now = System.currentTimeMillis();
        int index = 0;
        for (String ambientId : AMBIENT_GUEST_IDS) {
            GuestPersona persona = nextPersona();
            GuestSession session = sessionFrom(persona, ambientId, persona.city());
            session.setLastSeenEpochMs(now - (index * 45_000L));
            guestSessions.put(ambientId, session);
            index++;
        }
    }

    // --------------------------------------------------------------- personas

    /** Next persona from the rotating pool - never repeats until the pool wraps around. */
    public GuestPersona nextPersona() {
        int index = Math.floorMod(personaIndex.getAndIncrement(), PERSONAS.size());
        return PERSONAS.get(index);
    }

    /** Builds a fresh login-free session out of a persona template. */
    public static GuestSession sessionFrom(GuestPersona persona, String guestId, String cityOverride) {
        String city = cityOverride == null || cityOverride.isBlank() ? persona.city() : cityOverride;
        String neighborhood = city.equalsIgnoreCase(persona.city()) ? persona.neighborhood() : "City Center";
        GuestSession session = new GuestSession(guestId, persona.displayName(), persona.ageRole(),
                city, neighborhood, persona.lat(), persona.lng(), persona.format(), persona.pace(),
                persona.budgetMin(), persona.budgetMax(), persona.verificationBadges());
        session.setMiniGroupPriority(GuestSession.SOLO.equals(persona.format()));
        return session;
    }

    /** One entry of the rotating persona pool. */
    public record GuestPersona(String displayName, String ageRole, String city, String neighborhood,
                               double lat, double lng, String format, String pace,
                               int budgetMin, int budgetMax, List<String> verificationBadges) {
    }

    // ------------------------------------------------------- state revision

    /** Latest revision of the shared party/venue data; clients reload when this value changes. */
    public long getStateRevision() {
        return stateRevision.get();
    }

    /** Marks the shared data as changed so every open tab picks the update up on its next poll. */
    public void bumpRevision() {
        stateRevision.incrementAndGet();
    }

    // --------------------------------------------------------------- friends

    public List<Friend> getFriends() {
        return new ArrayList<>(friends);
    }

    public Optional<Friend> findFriend(String friendId) {
        return friends.stream().filter(f -> f.getId().equals(friendId)).findFirst();
    }

    // ---------------------------------------------------------------- venues

    public List<Venue> getVenues() {
        return new ArrayList<>(venues);
    }

    public Optional<Venue> findVenue(String venueId) {
        return venues.stream().filter(v -> v.getId().equals(venueId)).findFirst();
    }

    public Venue saveVenue(Venue venue) {
        venues.add(venue);
        bumpRevision();
        return venue;
    }

    public String nextVenueId() {
        return "v" + venueSequence.incrementAndGet();
    }

    /**
     * Dedupe guard for GPS seeding: any already-generated demo pool within 15 km of the caller is
     * reused instead of flooding the store with a second, nearly identical set of venues.
     */
    public boolean hasVenueNear(String city, double lat, double lng) {
        return venues.stream()
                .filter(Venue::isDemoPool)
                .anyMatch(venue -> haversineKm(lat, lng, venue.getLat(), venue.getLng()) <= 15.0);
    }

    // ---------------------------------------------------------------- parties

    public List<Party> getParties() {
        return new ArrayList<>(parties);
    }

    public List<Party> getOpenParties() {
        return parties.stream().filter(p -> Party.OPEN.equals(p.getStatus())).toList();
    }

    public Optional<Party> findParty(String partyId) {
        return parties.stream().filter(p -> p.getId().equals(partyId)).findFirst();
    }

    public Party saveParty(Party party) {
        parties.add(party);
        bumpRevision();
        return party;
    }

    public String nextPartyId() {
        return "p" + partySequence.incrementAndGet();
    }

    /** All OPEN slots hosted at one venue - what a tapped map pin needs to render. */
    public List<Party> openPartiesAtVenue(String venueId) {
        return parties.stream()
                .filter(p -> Party.OPEN.equals(p.getStatus()))
                .filter(p -> venueId != null && venueId.equals(p.getVenueId()))
                .toList();
    }

    /** Recomputes the live slot/queue counters shown on every venue card. */
    public void recountVenueLoad() {
        Map<String, Integer> activeSlots = new HashMap<>();
        Map<String, Integer> queued = new HashMap<>();
        for (Party party : parties) {
            if (party.getVenueId() == null) {
                continue;
            }
            if (Party.OPEN.equals(party.getStatus())) {
                activeSlots.merge(party.getVenueId(), 1, Integer::sum);
            }
            queued.merge(party.getVenueId(), party.getQueueGuestNames().size(), Integer::sum);
        }
        for (Venue venue : venues) {
            venue.setActiveSlotCount(activeSlots.getOrDefault(venue.getId(), 0));
            venue.setQueueCount(queued.getOrDefault(venue.getId(), 0));
        }
        bumpRevision();
    }

    // ------------------------------------------------------------ visit history

    public List<VisitRecord> getVisitHistory() {
        return new ArrayList<>(visitHistory);
    }

    public Optional<VisitRecord> findVisit(String visitId) {
        return visitHistory.stream().filter(v -> v.getId().equals(visitId)).findFirst();
    }

    public VisitRecord saveVisit(VisitRecord visit) {
        visitHistory.add(visit);
        return visit;
    }

    public String nextVisitId() {
        return "h" + visitSequence.incrementAndGet();
    }

    // --------------------------------------------------------- guest sessions

    public Optional<GuestSession> findGuest(String guestId) {
        return guestId == null ? Optional.empty() : Optional.ofNullable(guestSessions.get(guestId));
    }

    public GuestSession saveGuest(GuestSession session) {
        guestSessions.put(session.getGuestId(), session);
        return session;
    }

    public String nextGuestId() {
        return "g" + guestSequence.incrementAndGet();
    }

    /** Everyone currently in the store, most recently seen first. */
    public List<GuestSession> onlineGuests() {
        return guestSessions.values().stream()
                .sorted(Comparator.comparingLong(GuestSession::getLastSeenEpochMs).reversed())
                .toList();
    }

    /**
     * Drops sessions that stopped sending heartbeats (older than {@code staleBeforeMs}).
     * The ambient demo crowd is re-touched instead of being dropped.
     */
    public int cleanStaleSessions(long staleBeforeMs) {
        int removed = 0;
        long now = System.currentTimeMillis();
        for (GuestSession session : guestSessions.values()) {
            if (AMBIENT_GUEST_IDS.contains(session.getGuestId())) {
                session.setLastSeenEpochMs(now);
                continue;
            }
            if (session.getLastSeenEpochMs() < staleBeforeMs
                    && guestSessions.remove(session.getGuestId(), session)) {
                removed++;
            }
        }
        return removed;
    }

    // ------------------------------------------------------- friend relations

    public List<FriendRelation> getFriendRelations() {
        return new ArrayList<>(friendRelations);
    }

    public Optional<FriendRelation> findFriendRelation(String relationId) {
        return friendRelations.stream().filter(r -> r.getId().equals(relationId)).findFirst();
    }

    public FriendRelation saveFriendRelation(FriendRelation relation) {
        friendRelations.add(relation);
        return relation;
    }

    public String nextRelationId() {
        return "fr" + relationSequence.incrementAndGet();
    }

    public List<FriendRelation> acceptedRelationsFor(String guestId) {
        return friendRelations.stream()
                .filter(r -> FriendRelation.ACCEPTED.equals(r.getStatus()))
                .filter(r -> touches(r, guestId))
                .toList();
    }

    public List<FriendRelation> relationsBetween(String leftGuestId, String rightGuestId) {
        return friendRelations.stream()
                .filter(r -> (r.getFromGuestId().equals(leftGuestId) && r.getToGuestId().equals(rightGuestId))
                        || (r.getFromGuestId().equals(rightGuestId) && r.getToGuestId().equals(leftGuestId)))
                .toList();
    }

    public List<FriendRelation> pendingRequestsFor(String guestId) {
        return friendRelations.stream()
                .filter(r -> FriendRelation.PENDING.equals(r.getStatus()))
                .filter(r -> r.getToGuestId().equals(guestId))
                .toList();
    }

    // ---------------------------------------------------------- slot invites

    public List<SlotInvite> getSlotInvites() {
        return new ArrayList<>(slotInvites);
    }

    public Optional<SlotInvite> findInvite(String inviteId) {
        return slotInvites.stream().filter(i -> i.getId().equals(inviteId)).findFirst();
    }

    public SlotInvite saveInvite(SlotInvite invite) {
        slotInvites.add(invite);
        return invite;
    }

    public String nextInviteId() {
        return "si" + inviteSequence.incrementAndGet();
    }

    public List<SlotInvite> pendingInvitesFor(String guestId) {
        return slotInvites.stream()
                .filter(i -> SlotInvite.PENDING.equals(i.getStatus()))
                .filter(i -> i.getToGuestId().equals(guestId))
                .toList();
    }

    // ------------------------------------------------------------ radar feed

    public List<RadarEvent> getRadarEvents() {
        return new ArrayList<>(radarEvents);
    }

    public RadarEvent saveRadarEvent(RadarEvent event) {
        radarEvents.add(event);
        return event;
    }

    public String nextRadarId() {
        return "r" + radarSequence.incrementAndGet();
    }

    // --------------------------------------------------------------- helpers

    private boolean touches(FriendRelation relation, String guestId) {
        return relation.getFromGuestId().equals(guestId) || relation.getToGuestId().equals(guestId);
    }

    /** Great-circle distance in kilometres - used by the GPS dedupe guard. */
    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double earthRadiusKm = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return earthRadiusKm * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
