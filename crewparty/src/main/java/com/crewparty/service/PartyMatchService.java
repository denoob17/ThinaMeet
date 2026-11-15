package com.crewparty.service;

import com.crewparty.model.CreatePartyRequest;
import com.crewparty.model.Friend;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic matching, live seating/midpoint recalculation, presence and the social
 * (friends, invites, check-in, profile) flows. Everything here is pure in-memory logic on top of
 * {@link InMemoryStoreRepository}, so the same inputs always produce the same outputs.
 */
@Service
public class PartyMatchService {

    /** Themes that mean "no filter" when they arrive from the UI. */
    private static final String ANY_THEME = "All Themes";
    private static final String ANY_CITY = "All Cities";

    /** Sentinel used by format/pace filters: it disables the filter instead of matching a literal. */
    private static final String ANY = "All";

    private static final int THEME_WEIGHT = 45;
    private static final int CITY_WEIGHT = 25;
    private static final int BUDGET_WEIGHT = 20;
    private static final int SLOTS_WEIGHT = 10;

    /** Results below this fit score are hidden: with active filters they simply do not match. */
    private static final int MIN_MATCH_SCORE = 65;

    /** A tab that has not sent a heartbeat for this long is considered offline. */
    private static final long PRESENCE_STALE_MS = 5 * 60 * 1000L;

    /** Shown while a midpoint party has not locked onto a real public spot yet. */
    private static final String MIDPOINT_PLACEHOLDER = "TBD • Auto-Midpoint Public Spot";

    private static final String HOST = "You (Host)";

    /** Keywords used to guess whether a venue fits a theme. */
    private static final List<String> GAMES_KEYWORDS =
            List.of("game", "board", "gaming", "esports", "cafe");
    private static final List<String> COWORK_KEYWORDS =
            List.of("cowork", "co-work", "lounge", "quiet", "wifi", "wi-fi");
    private static final List<String> BAR_KEYWORDS =
            List.of("bar", "gastropub", "pub", "tavern", "trivia");

    /**
     * Deterministic layout of the on-the-fly demo pool: each template maps to one public venue and
     * one matching open slot placed a few hundred metres from the caller's coordinates.
     */
    private static final List<SeedTemplate> SEED_TEMPLATES = List.of(
            new SeedTemplate("North Loop Board Game Cafe", "Board Game Cafe", "Board Games",
                    List.of("Great Table Space", "Budget Friendly"), 18, 0.012, 0.006, "Sat 4:00 PM",
                    GuestSession.SOLO, GuestSession.CHILL),
            new SeedTemplate("Riverside Coworking & Chill", "Coworking Lounge", "Coworking & Chill",
                    List.of("Great Wi-Fi", "Quiet Enough to Talk"), 12, -0.009, 0.014, "Sun 11:00 AM",
                    GuestSession.SOLO, GuestSession.CHILL),
            new SeedTemplate("Midtown Sports & Rec Meetup", "Sports & Rec Center", "Active / Sports",
                    List.of("Open Courts", "Budget Friendly"), 10, 0.005, -0.016, "Sat 9:00 AM",
                    GuestSession.COUPLES, GuestSession.ACTIVE),
            new SeedTemplate("Corner Coffee & Trivia", "Coffee & Trivia House", "Coffee & Trivia",
                    List.of("Craft Drinks", "Quiet Enough to Talk"), 8, -0.015, -0.008, "Thu 8:00 PM",
                    GuestSession.SOLO, GuestSession.CHILL),
            new SeedTemplate("Old Town Tabletop Lounge", "Board Game Cafe", "Board Games",
                    List.of("Huge Game Library", "Late Night"), 22, 0.019, 0.011, "Fri 7:00 PM",
                    GuestSession.COUPLES, GuestSession.CHILL),
            new SeedTemplate("Harbor View Park Meetup", "Public Park Meetup", "Active / Sports",
                    List.of("Outdoor Casual", "Budget Friendly"), 0, -0.020, 0.004, "Sun 5:00 PM",
                    GuestSession.FAMILY, GuestSession.ACTIVE),
            new SeedTemplate("Central Library Coworking Corner", "Coworking Lounge", "Coworking & Chill",
                    List.of("Great Wi-Fi", "Budget Friendly"), 5, 0.002, 0.020, "Sat 1:00 PM",
                    GuestSession.SOLO, GuestSession.CHILL));

    private final InMemoryStoreRepository repository;
    private final AiIcebreakerService icebreakerService;

    public PartyMatchService(InMemoryStoreRepository repository, AiIcebreakerService icebreakerService) {
        this.repository = repository;
        this.icebreakerService = icebreakerService;
    }

    // ---------------------------------------------------------------- matching

    /**
     * Transparent fit score (0-100) of an open party against the current search filters.
     * Every contribution is deterministic and is explained in {@link FitResult#reasons()},
     * so the UI can always justify why a party surfaced.
     */
    public FitResult calculateFitScore(Party party, String theme, String city, int maxBudget) {
        int score = 0;
        List<String> reasons = new ArrayList<>();

        if (isAny(theme, ANY_THEME)) {
            score += THEME_WEIGHT;
            reasons.add("No theme filter — every theme counts (+" + THEME_WEIGHT + ")");
        } else if (equalsIgnoreCase(party.getTheme(), theme)) {
            score += THEME_WEIGHT;
            reasons.add("Theme matches \"" + theme + "\" (+" + THEME_WEIGHT + ")");
        } else {
            reasons.add("Theme is \"" + party.getTheme() + "\", not your \"" + theme + "\" filter (+0)");
        }

        if (isAny(city, ANY_CITY)) {
            score += CITY_WEIGHT;
            reasons.add("No city filter — every city counts (+" + CITY_WEIGHT + ")");
        } else if (equalsIgnoreCase(party.getCity(), city)) {
            score += CITY_WEIGHT;
            reasons.add("Happening in " + party.getCity() + " (+" + CITY_WEIGHT + ")");
        } else {
            reasons.add("Happening in " + party.getCity() + ", not " + city + " (+0)");
        }

        if (maxBudget <= 0) {
            score += BUDGET_WEIGHT;
            reasons.add("No budget limit set (+" + BUDGET_WEIGHT + ")");
        } else if (party.getBudgetMin() <= maxBudget) {
            score += BUDGET_WEIGHT;
            reasons.add("$" + party.getBudgetMin() + "-$" + party.getBudgetMax()
                    + " fits your $" + maxBudget + " limit (+" + BUDGET_WEIGHT + ")");
        } else if (party.getBudgetMin() <= maxBudget + 10) {
            score += BUDGET_WEIGHT / 2;
            reasons.add("Slightly over budget ($" + party.getBudgetMin() + " vs $" + maxBudget
                    + ") (+" + (BUDGET_WEIGHT / 2) + ")");
        } else {
            reasons.add("Starts at $" + party.getBudgetMin() + ", above your $" + maxBudget
                    + " limit (+0)");
        }

        if (party.getOpenSlots() > 0) {
            score += SLOTS_WEIGHT;
            reasons.add(party.getOpenSlots() + " slot(s) still open (+" + SLOTS_WEIGHT + ")");
        } else {
            reasons.add("Party is already full (+0)");
        }

        return new FitResult(score, List.copyOf(reasons));
    }

    /**
     * Recomputes the party's fit score for the given filters and stores both the number and its
     * human-readable breakdown on the party itself, so they travel to the frontend in every payload.
     */
    public Party scoreParty(Party party, String theme, String city, int maxBudget) {
        FitResult fit = calculateFitScore(party, theme, city, maxBudget);
        party.setFitScore(fit.score());
        party.setMatchReasons(fit.reasons());
        return party;
    }

    /** The integer fit score plus the reasons behind it. */
    public record FitResult(int score, List<String> reasons) {
    }

    /** Everything {@code GET /api/search} returns, built by the service and serialized by the controller. */
    public record SearchOutcome(List<Party> parties, List<Friend> players, List<Venue> venues) {
    }

    /** Fit score (0-100) of a discoverable player against the current search filters. */
    public int calculatePlayerFitScore(Friend player, String theme, String city) {
        int score = 0;

        if (isAny(theme, ANY_THEME)) {
            score += THEME_WEIGHT;
        } else if (player.getThemes().stream().anyMatch(t -> equalsIgnoreCase(t, theme))) {
            score += THEME_WEIGHT;
        }

        if (isAny(city, ANY_CITY) || equalsIgnoreCase(player.getCity(), city)) {
            score += CITY_WEIGHT + SLOTS_WEIGHT;
        }

        if (player.isVerified()) {
            score += BUDGET_WEIGHT / 2;
        }
        if (Friend.DISCOVER.equals(player.getStatus())) {
            score += BUDGET_WEIGHT / 2;
        }

        return score;
    }

    /** The theme stays a soft filter for venues: it never hides a place the crew could still use. */
    public boolean matchesVenueFilters(Venue venue, String city) {
        return isAny(city, ANY_CITY) || equalsIgnoreCase(venue.getCity(), city);
    }

    public List<Friend> playersMatching(String theme, String city, boolean verifiedOnly) {
        return repository.getFriends().stream()
                .filter(player -> !Friend.CORE_BUDDY.equals(player.getStatus()))
                .filter(player -> !verifiedOnly || player.isVerified())
                .map(player -> new ScoredPlayer(player, calculatePlayerFitScore(player, theme, city)))
                .filter(scored -> scored.score() >= MIN_MATCH_SCORE)
                .sorted(Comparator.comparingInt(ScoredPlayer::score).reversed())
                .map(ScoredPlayer::player)
                .toList();
    }

    public List<Venue> venuesMatching(String theme, String city, boolean publicSafeOnly) {
        return repository.getVenues().stream()
                .filter(venue -> matchesVenueFilters(venue, city))
                .filter(venue -> !publicSafeOnly || venue.isPublicSafeSpot())
                .sorted(Comparator.comparing((Venue venue) -> themeFitRank(venue, theme)).reversed()
                        .thenComparing(Comparator.comparingDouble(Venue::getAvgRating).reversed()))
                .toList();
    }

    /** 2 = the venue's category or tags mention the theme, 1 = it does not. Used only for ordering. */
    private int themeFitRank(Venue venue, String theme) {
        if (isAny(theme, ANY_THEME)) {
            return 1;
        }
        String haystack = (venue.getCategory() + " " + String.join(" ", venue.getTags())).toLowerCase(Locale.ROOT);
        return keywordsFor(theme).stream().anyMatch(haystack::contains) ? 2 : 1;
    }

    // ------------------------------------------------------------ live search

    /**
     * Advanced deterministic search. Legacy filters (theme + maxBudget) keep their scoring behaviour;
     * the new filters (budget window, format, pace, safety/trust flags, friendsOnly) are strict.
     */
    public SearchOutcome search(String guestId, String city, String theme, String timeWindow,
                                int budgetMin, int budgetMax, String format, String pace,
                                boolean friendsOnly, boolean publicSafeOnly, boolean verifiedOnly,
                                boolean miniGroupOnly) {
        GuestSession caller = findGuest(guestId);
        int scoringBudget = budgetMax >= 0 ? budgetMax : 0;
        Set<String> friendIds = friendsOnly ? acceptedFriendIds(guestId) : Set.of();
        boolean strict = budgetMax >= 0 || isActiveFilter(format) || isActiveFilter(pace)
                || publicSafeOnly || verifiedOnly || miniGroupOnly || friendsOnly;

        List<Party> parties = new ArrayList<>();
        for (Party party : repository.getOpenParties()) {
            scoreParty(party, theme, city, scoringBudget);
            party.setBudgetCompliant(budgetMax < 0
                    || (party.getBudgetMin() <= budgetMax && party.getBudgetMax() >= budgetMin));
            party.setDistanceKm(caller == null ? 0
                    : round1(haversineKm(caller.getLat(), caller.getLng(), party.getLat(), party.getLng())));

            if (isActiveFilter(timeWindow)) {
                List<String> reasons = new ArrayList<>(party.getMatchReasons());
                boolean fits = party.getTimeLabel() != null
                        && party.getTimeLabel().toLowerCase(Locale.ROOT).contains(timeWindow.toLowerCase(Locale.ROOT));
                reasons.add(fits ? "Fits your \"" + timeWindow + "\" window"
                        : "Scheduled for " + party.getTimeLabel());
                party.setMatchReasons(reasons);
            }

            if (strict) {
                if (!matchesStrict(party, budgetMin, budgetMax, format, pace, friendsOnly, friendIds,
                        publicSafeOnly, verifiedOnly, miniGroupOnly)) {
                    continue;
                }
            } else if (party.getFitScore() < MIN_MATCH_SCORE) {
                continue;
            }
            parties.add(party);
        }
        parties.sort(Comparator.comparingInt(Party::getFitScore).reversed());

        return new SearchOutcome(
                parties,
                playersMatching(theme, city, verifiedOnly),
                venuesMatching(theme, city, publicSafeOnly));
    }

    private boolean matchesStrict(Party party, int budgetMin, int budgetMax, String format, String pace,
                                  boolean friendsOnly, Set<String> friendIds, boolean publicSafeOnly,
                                  boolean verifiedOnly, boolean miniGroupOnly) {
        if (budgetMax >= 0 && !(party.getBudgetMin() <= budgetMax && party.getBudgetMax() >= budgetMin)) {
            return false;
        }
        if (isActiveFilter(format) && !equalsIgnoreCase(party.getFormat(), format)) {
            return false;
        }
        if (isActiveFilter(pace) && !equalsIgnoreCase(party.getPace(), pace)) {
            return false;
        }
        if (miniGroupOnly && !party.isMiniGroupPriority()) {
            return false;
        }
        if (verifiedOnly && party.getTrustBadges().isEmpty()) {
            return false;
        }
        if (publicSafeOnly) {
            Venue venue = party.getVenueId() == null ? null
                    : repository.findVenue(party.getVenueId()).orElse(null);
            if (venue == null || !venue.isPublicSafeSpot()) {
                return false;
            }
        }
        if (friendsOnly) {
            if (friendIds.isEmpty() || party.getMemberGuestIds().stream().noneMatch(friendIds::contains)) {
                return false;
            }
        }
        return true;
    }

    // ----------------------------------------------------- multi-tab sessions

    /**
     * Mints (or refreshes) a login-free identity for one browser tab. An unknown {@code guestId}
     * still gets a brand new persona, which is how two open tabs end up as two different people.
     */
    public GuestSession initSession(SessionInitRequest request) {
        String requestedId = request == null ? null : request.guestId();
        String city = request == null ? null : request.city();
        long now = System.currentTimeMillis();

        if (requestedId != null && !requestedId.isBlank()) {
            Optional<GuestSession> existing = repository.findGuest(requestedId);
            if (existing.isPresent()) {
                GuestSession session = existing.get();
                session.setLastSeenEpochMs(now);
                if (city != null && !city.isBlank()) {
                    session.setCity(city.trim());
                }
                return session;
            }
            GuestSession fresh = InMemoryStoreRepository.sessionFrom(repository.nextPersona(), requestedId, city);
            fresh.setLastSeenEpochMs(now);
            return repository.saveGuest(fresh);
        }

        GuestSession fresh = InMemoryStoreRepository.sessionFrom(
                repository.nextPersona(), repository.nextGuestId(), city);
        fresh.setLastSeenEpochMs(now);
        return repository.saveGuest(fresh);
    }

    /** Heartbeat: ages out stale tabs, then reports who is online and what is waiting for an answer. */
    public PresenceResponse presence(String guestId) {
        long now = System.currentTimeMillis();
        repository.cleanStaleSessions(now - PRESENCE_STALE_MS);

        GuestSession current = findGuest(guestId);
        if (current != null) {
            current.setLastSeenEpochMs(now);
        }

        return new PresenceResponse(
                current,
                repository.onlineGuests(),
                repository.acceptedRelationsFor(guestId),
                repository.pendingRequestsFor(guestId),
                repository.pendingInvitesFor(guestId));
    }

    // -------------------------------------------------------------- GPS seeding

    /**
     * Generates a fresh public demo pool (6-8 venues + matching open slots) clustered within ~3 km
     * of the caller. Repeated calls for the same city/cell return the already generated pool instead
     * of flooding the store with duplicates.
     */
    public GpsSeedResponse gpsSeed(GpsSeedRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GPS coordinates are required.");
        }
        String city = resolveCity(request.cityName(), request.lat(), request.lng());

        if (repository.hasVenueNear(city, request.lat(), request.lng())) {
            List<Venue> existingVenues = repository.getVenues().stream()
                    .filter(Venue::isDemoPool)
                    .filter(venue -> sameCity(venue.getCity(), city))
                    .toList();
            List<Party> existingParties = existingVenues.stream()
                    .flatMap(venue -> repository.openPartiesAtVenue(venue.getId()).stream())
                    .toList();
            return new GpsSeedResponse(city, request.lat(), request.lng(),
                    0, 0, existingVenues, existingParties, true);
        }

        List<Venue> createdVenues = new ArrayList<>();
        List<Party> createdParties = new ArrayList<>();
        for (SeedTemplate template : SEED_TEMPLATES) {
            Venue venue = new Venue(repository.nextVenueId(), template.name(), template.category(), city, "Near You",
                    template.cost(), 4.5, 20 + template.cost(), template.tags(),
                    request.lat() + template.dLat(), request.lng() + template.dLng());
            venue.setPublicSafeSpot(true);
            venue.setDemoPool(true);
            repository.saveVenue(venue);
            createdVenues.add(venue);

            Party party = new Party(repository.nextPartyId(),
                    template.theme() + " crew forming near you", template.theme(), city, "Near You",
                    4, 1, template.cost(), template.cost() + 15, template.timeLabel(),
                    venue.getId(), venue.getName(), List.of(HOST),
                    List.of("Kick things off: what are you hoping to find in a new crew?"),
                    Party.OPEN);
            party.setFormat(template.format());
            party.setPace(template.pace());
            party.setMiniGroupPriority(GuestSession.SOLO.equals(template.format()));
            party.setTrustBadges(List.of("ID Verified", "Shared Interests"));
            party.setDemoPool(true);
            party.setLat(venue.getLat());
            party.setLng(venue.getLng());
            scoreParty(party, party.getTheme(), city, 0);
            repository.saveParty(party);
            createdParties.add(party);
        }
        repository.recountVenueLoad();
        repository.markSeeded(city, request.lat(), request.lng());

        return new GpsSeedResponse(city, request.lat(), request.lng(),
                createdVenues.size(), createdParties.size(), createdVenues, createdParties, true);
    }

    /** Every OPEN slot hosted at one venue - what a tapped map pin renders. */
    public VenueSlotsResponse venueSlots(String venueId) {
        Venue venue = repository.findVenue(venueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Venue not found: " + venueId));
        repository.recountVenueLoad();
        List<Party> slots = repository.openPartiesAtVenue(venueId).stream()
                .map(party -> scoreParty(party, null, venue.getCity(), 0))
                .toList();
        return new VenueSlotsResponse(venue, slots, venue.getActiveSlotCount(), venue.getQueueCount());
    }

    /**
     * Weekly Free &amp; Low-Budget Event Radar: curated weekend free museum days, local fairs and
     * public park meetups for one city, filtered to what the caller can actually afford.
     * A {@code budgetMax} below zero means "no limit".
     */
    public List<RadarEvent> radar(String city, int budgetMax) {
        return repository.getRadarEvents().stream()
                .filter(event -> isAny(city, ANY_CITY) || equalsIgnoreCase(event.getCity(), city))
                .filter(event -> event.getCostPerPerson() == 0
                        || budgetMax < 0
                        || event.getCostPerPerson() <= budgetMax)
                .sorted(Comparator.comparingInt(RadarEvent::getCostPerPerson))
                .toList();
    }

    /** Adds a guest to the venue queue, but only when the slot is already at capacity. */
    public Party queueForSlot(String partyId, QueueRequest request) {
        Party party = repository.findParty(partyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Party not found: " + partyId));
        if (Party.COMPLETED.equals(party.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This party has already happened.");
        }
        if (party.getOpenSlots() > 0) {
            return party;
        }

        String name = request != null && request.guestName() != null && !request.guestName().isBlank()
                ? request.guestName().trim()
                : "Guest";
        List<String> queue = new ArrayList<>(party.getQueueGuestNames());
        if (!queue.contains(name)) {
            queue.add(name);
            party.setQueueGuestNames(queue);
        }
        repository.recountVenueLoad();
        return party;
    }

    // -------------------------------------------------- party create & join

    /** Creates a party, either at an explicit venue or in auto-midpoint mode (venue left TBD). */
    public Party createParty(CreatePartyRequest request) {
        if (request.title() == null || request.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give your party a title.");
        }
        if (request.theme() == null || request.theme().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick a theme for your party.");
        }

        GuestSession creator = findGuest(request.guestId());

        Venue venue = null;
        if (request.venueId() != null && !request.venueId().isBlank()) {
            venue = repository.findVenue(request.venueId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick a venue for your party."));
        } else if (!request.midpointMode()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick a venue for your party.");
        }

        String city = firstNonBlank(request.city(),
                creator == null ? null : creator.getCity(),
                venue == null ? null : venue.getCity(), "Chicago");
        String neighborhood = firstNonBlank(request.neighborhood(),
                creator == null ? null : creator.getNeighborhood(),
                venue == null ? null : venue.getNeighborhood(), "City Center");

        List<String> members = new ArrayList<>();
        List<String> memberNames = new ArrayList<>();
        members.add(creator == null ? HOST : creator.getDisplayName());
        request.buddyIds().stream()
                .map(repository::findFriend)
                .flatMap(Optional::stream)
                .map(Friend::getName)
                .forEach(members::add);

        List<String> memberGuestIds = new ArrayList<>();
        if (creator != null) {
            memberGuestIds.add(creator.getGuestId());
        }
        memberNames.addAll(members);

        int totalSlots = Math.max(request.totalSlots(), members.size());
        boolean midpoint = request.midpointMode() || venue == null;
        // An explicit venue party lives at the venue (so distanceKm is "you -> the spot"); a midpoint
        // party starts at the creator and is snapped to the fair average once the first guest joins.
        double lat;
        double lng;
        if (!midpoint) {
            lat = venue.getLat();
            lng = venue.getLng();
        } else {
            lat = creator != null ? creator.getLat() : (venue != null ? venue.getLat() : 0);
            lng = creator != null ? creator.getLng() : (venue != null ? venue.getLng() : 0);
        }

        Party party = new Party(
                repository.nextPartyId(),
                request.title().trim(),
                request.theme(),
                city,
                neighborhood,
                totalSlots,
                members.size(),
                request.budgetMin(),
                Math.max(request.budgetMax(), request.budgetMin()),
                firstNonBlank(request.timeLabel(), "This weekend"),
                venue == null ? null : venue.getId(),
                venue == null ? MIDPOINT_PLACEHOLDER : venue.getName(),
                members,
                icebreakerService.generateIcebreakers(request.theme(), request.title(), city),
                Party.OPEN);

        party.setMidpointMode(midpoint);
        party.setLat(lat);
        party.setLng(lng);
        party.setMemberGuestIds(memberGuestIds);
        party.setMemberNames(memberNames);
        party.setFormat(firstNonBlank(request.format(),
                creator == null ? null : creator.getFormat(), GuestSession.SOLO));
        party.setPace(firstNonBlank(request.pace(),
                creator == null ? null : creator.getPace(), GuestSession.CHILL));
        party.setMiniGroupPriority(request.miniGroupPriority());
        if (creator != null && !creator.getVerificationBadges().isEmpty()) {
            party.setTrustBadges(creator.getVerificationBadges());
        }

        scoreParty(party, party.getTheme(), party.getCity(), 0);
        Party saved = repository.saveParty(party);
        repository.recountVenueLoad();
        return saved;
    }

    /**
     * Joins a party either as a guest session ({@code guestId}, which carries coordinates and
     * therefore feeds the midpoint average) or as a legacy demo friend ({@code friendId}).
     */
    public Party joinParty(String partyId, String guestId, String friendId) {
        Party party = repository.findParty(partyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Party not found: " + partyId));

        if (Party.COMPLETED.equals(party.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This party has already happened.");
        }

        if (guestId != null && !guestId.isBlank()) {
            GuestSession guest = requireGuest(guestId);
            if (party.getOpenSlots() <= 0 && !party.getMemberGuestIds().contains(guest.getGuestId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This party is already full. Join the queue instead.");
            }
            attachGuest(party, guest);
        } else {
            if (party.getOpenSlots() <= 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This party is already full.");
            }
            Friend friend = repository.findFriend(friendId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found: " + friendId));
            if (party.getMembers().contains(friend.getName())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, friend.getName() + " is already in this party.");
            }
            List<String> members = new ArrayList<>(party.getMembers());
            members.add(friend.getName());
            party.setMembers(members);
            party.setFilledSlots(members.size());
        }

        if (party.isMidpointMode()) {
            recalcMidpoint(party);
        }
        repository.recountVenueLoad();
        return party;
    }

    // ----------------------------------------------------- friends & invites

    public FriendRelation requestFriend(String fromGuestId, String toGuestId) {
        GuestSession from = requireGuest(fromGuestId);
        GuestSession to = requireGuest(toGuestId);
        if (from.getGuestId().equals(to.getGuestId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot add yourself.");
        }
        for (FriendRelation existing : repository.relationsBetween(from.getGuestId(), to.getGuestId())) {
            if (FriendRelation.ACCEPTED.equals(existing.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        to.getDisplayName() + " is already in your crew.");
            }
            if (FriendRelation.PENDING.equals(existing.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A friend request with " + to.getDisplayName() + " is already pending.");
            }
        }
        return repository.saveFriendRelation(new FriendRelation(
                repository.nextRelationId(),
                from.getGuestId(), from.getDisplayName(),
                to.getGuestId(), to.getDisplayName(),
                FriendRelation.PENDING));
    }

    public FriendRelation acceptFriendRequest(String requestId) {
        FriendRelation relation = repository.findFriendRelation(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Friend request not found: " + requestId));
        if (!FriendRelation.PENDING.equals(relation.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This friend request was already answered.");
        }
        relation.setStatus(FriendRelation.ACCEPTED);
        return relation;
    }

    public SlotInvite createInvite(String fromGuestId, String toGuestId, String partyId) {
        GuestSession from = requireGuest(fromGuestId);
        GuestSession to = requireGuest(toGuestId);
        Party party = repository.findParty(partyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Party not found: " + partyId));

        return repository.saveInvite(new SlotInvite(
                repository.nextInviteId(),
                from.getGuestId(), from.getDisplayName(),
                to.getGuestId(), party.getId(), party.getTitle(), party.getVenueName(),
                SlotInvite.PENDING, System.currentTimeMillis()));
    }

    /** Accepting an invite drops the guest straight into the target party (and recomputes the midpoint). */
    public SlotInvite respondToInvite(String inviteId, String action) {
        SlotInvite invite = repository.findInvite(inviteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invite not found: " + inviteId));
        if (!SlotInvite.PENDING.equals(invite.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This invite was already answered.");
        }

        String normalized = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        if ("ACCEPT".equals(normalized)) {
            invite.setStatus(SlotInvite.ACCEPTED);
            repository.findParty(invite.getPartyId()).ifPresent(party -> {
                GuestSession guest = findGuest(invite.getToGuestId());
                if (guest != null) {
                    attachGuest(party, guest);
                    if (party.isMidpointMode()) {
                        recalcMidpoint(party);
                    }
                    repository.recountVenueLoad();
                }
            });
        } else if ("DECLINE".equals(normalized)) {
            invite.setStatus(SlotInvite.DECLINED);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Action must be ACCEPT or DECLINE.");
        }
        return invite;
    }

    /** Records a safety check-in so the crew knows a guest actually arrived at the public spot. */
    public Party checkIn(String partyId, String guestId) {
        Party party = repository.findParty(partyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Party not found: " + partyId));
        requireGuest(guestId);

        List<String> checkedIn = new ArrayList<>(party.getCheckedInGuestIds());
        if (!checkedIn.contains(guestId)) {
            checkedIn.add(guestId);
            party.setCheckedInGuestIds(checkedIn);
        }
        return party;
    }

    // -------------------------------------------------------------- profile

    /** Patches the guest profile; every {@code null} field means "leave it as is". */
    public GuestSession updateProfile(ProfileUpdateRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A profile payload is required.");
        }
        GuestSession session = requireGuest(request.guestId());

        if (request.displayName() != null && !request.displayName().isBlank()) {
            session.setDisplayName(request.displayName().trim());
        }
        if (request.ageRole() != null && !request.ageRole().isBlank()) {
            session.setAgeRole(request.ageRole().trim());
        }
        if (request.city() != null && !request.city().isBlank()) {
            session.setCity(request.city().trim());
        }
        if (request.neighborhood() != null && !request.neighborhood().isBlank()) {
            session.setNeighborhood(request.neighborhood().trim());
        }
        if (request.budgetMin() != null) {
            session.setBudgetMin(Math.max(0, request.budgetMin()));
        }
        if (request.budgetMax() != null) {
            session.setBudgetMax(Math.max(session.getBudgetMin(), request.budgetMax()));
        }
        if (request.format() != null && !request.format().isBlank()) {
            session.setFormat(request.format().trim());
        }
        if (request.pace() != null && !request.pace().isBlank()) {
            session.setPace(request.pace().trim());
        }
        if (request.publicVenuesOnly() != null) {
            session.setPublicVenuesOnly(request.publicVenuesOnly());
        }
        if (request.miniGroupPriority() != null) {
            session.setMiniGroupPriority(request.miniGroupPriority());
        }
        if (request.verificationBadges() != null) {
            session.setVerificationBadges(request.verificationBadges());
        }
        session.setLastSeenEpochMs(System.currentTimeMillis());
        return session;
    }

    // ------------------------------------------------------------ party state

    /** Marks an open party as COMPLETED and pushes it into the visit history so it can be rated. */
    public VisitRecord completeParty(String partyId) {
        Party party = repository.findParty(partyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Party not found: " + partyId));

        if (Party.COMPLETED.equals(party.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Party is already completed: " + partyId);
        }

        party.setStatus(Party.COMPLETED);
        party.setFilledSlots(party.getMembers().size());

        VisitRecord record = new VisitRecord(
                repository.nextVisitId(),
                party.getId(),
                party.getTitle(),
                party.getTheme(),
                party.getVenueId(),
                party.getVenueName(),
                LocalDate.now().toString(),
                new ArrayList<>(party.getMembers()),
                null,
                List.of(),
                "");

        repository.recountVenueLoad();
        return repository.saveVisit(record);
    }

    /** Stores a 1-5 star rating, the vibe tags and the comment, then refreshes the venue average. */
    public VisitRecord rateVisit(String visitId, int rating, List<String> tags, String comment) {
        if (rating < 1 || rating > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rating must be between 1 and 5 stars.");
        }

        VisitRecord record = repository.findVisit(visitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Visit not found: " + visitId));

        Integer previousRating = record.getRating();
        record.setRating(rating);
        record.setSelectedTags(new ArrayList<>(tags));
        record.setReviewComment(comment == null ? "" : comment.trim());

        repository.findVenue(record.getVenueId()).ifPresent(venue -> applyRating(venue, previousRating, rating));

        return record;
    }

    public Friend addFriend(String friendId) {
        Friend friend = repository.findFriend(friendId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found: " + friendId));

        if (Friend.CORE_BUDDY.equals(friend.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, friend.getName() + " is already your core buddy.");
        }
        friend.setStatus(Friend.FRIEND);
        return friend;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Recomputes the geographic average of every joined member and snaps the party to the closest
     * public safe spot that still fits the party budget.
     */
    private void recalcMidpoint(Party party) {
        List<double[]> coords = new ArrayList<>();
        for (String memberGuestId : party.getMemberGuestIds()) {
            repository.findGuest(memberGuestId).ifPresent(guest -> {
                if (guest.getLat() != 0 || guest.getLng() != 0) {
                    coords.add(new double[]{guest.getLat(), guest.getLng()});
                }
            });
        }
        if (coords.isEmpty()) {
            if (party.getLat() == 0 && party.getLng() == 0) {
                return;
            }
            coords.add(new double[]{party.getLat(), party.getLng()});
        }

        double avgLat = coords.stream().mapToDouble(c -> c[0]).average().orElse(0);
        double avgLng = coords.stream().mapToDouble(c -> c[1]).average().orElse(0);
        party.setLat(avgLat);
        party.setLng(avgLng);

        Venue best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Venue venue : repository.getVenues()) {
            if (!venue.isPublicSafeSpot() || venue.getAvgCostUsd() > party.getBudgetMax()) {
                continue;
            }
            double distance = haversineKm(avgLat, avgLng, venue.getLat(), venue.getLng());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = venue;
            }
        }

        if (best != null) {
            party.setVenueId(best.getId());
            party.setVenueName(best.getName());
            party.setLat(best.getLat());
            party.setLng(best.getLng());
            party.setDistanceKm(round1(bestDistance));
        } else {
            party.setVenueName(MIDPOINT_PLACEHOLDER);
        }
    }

    /** Adds the guest to the party roster without a capacity check (invites may reserve a slot). */
    private void attachGuest(Party party, GuestSession guest) {
        if (party.getMemberGuestIds().contains(guest.getGuestId())) {
            return;
        }
        List<String> memberGuestIds = new ArrayList<>(party.getMemberGuestIds());
        memberGuestIds.add(guest.getGuestId());
        party.setMemberGuestIds(memberGuestIds);

        List<String> memberNames = new ArrayList<>(party.getMemberNames());
        memberNames.add(guest.getDisplayName());
        party.setMemberNames(memberNames);

        List<String> members = new ArrayList<>(party.getMembers());
        members.add(guest.getDisplayName());
        party.setMembers(members);
        party.setFilledSlots(members.size());
    }

    private Set<String> acceptedFriendIds(String guestId) {
        if (guestId == null || guestId.isBlank()) {
            return Set.of();
        }
        Set<String> ids = new HashSet<>();
        for (FriendRelation relation : repository.acceptedRelationsFor(guestId)) {
            ids.add(relation.getFromGuestId().equals(guestId) ? relation.getToGuestId() : relation.getFromGuestId());
        }
        return ids;
    }

    private GuestSession requireGuest(String guestId) {
        if (guestId == null || guestId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A guestId is required.");
        }
        return repository.findGuest(guestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Guest session not found: " + guestId));
    }

    private GuestSession findGuest(String guestId) {
        return guestId == null || guestId.isBlank() ? null : repository.findGuest(guestId).orElse(null);
    }

    /** Labels the generated pool: explicit name, else the closest known demo city, else a neutral one. */
    private String resolveCity(String cityName, double lat, double lng) {
        if (cityName != null && !cityName.isBlank()) {
            return cityName.trim();
        }
        Venue nearest = null;
        double bestDistance = Double.MAX_VALUE;
        for (Venue venue : repository.getVenues()) {
            double distance = haversineKm(lat, lng, venue.getLat(), venue.getLng());
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = venue;
            }
        }
        return nearest != null && bestDistance <= 120 ? nearest.getCity() : "Your Area";
    }

    private void applyRating(Venue venue, Integer previousRating, int newRating) {
        if (previousRating == null) {
            venue.setRatingCount(venue.getRatingCount() + 1);
            venue.setRatingSum(venue.getRatingSum() + newRating);
        } else {
            venue.setRatingSum(venue.getRatingSum() - previousRating + newRating);
        }
        double average = venue.getRatingSum() / venue.getRatingCount();
        venue.setAvgRating(Math.round(average * 10.0) / 10.0);
    }

    /** Great-circle distance in kilometres. */
    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double earthRadiusKm = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return earthRadiusKm * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private List<String> keywordsFor(String theme) {
        if (theme == null) {
            return GAMES_KEYWORDS;
        }
        String normalized = theme.toLowerCase(Locale.ROOT);
        if (normalized.contains("cowork")) {
            return COWORK_KEYWORDS;
        }
        if (normalized.contains("trivia") || normalized.contains("outdoor")) {
            return BAR_KEYWORDS;
        }
        return GAMES_KEYWORDS;
    }

    private boolean isAny(String value, String sentinel) {
        return value == null || value.isBlank() || equalsIgnoreCase(value, sentinel);
    }

    /** Only a real format/pace/window value activates a strict filter: {@code All} keeps everything. */
    private boolean isActiveFilter(String value) {
        return value != null && !value.isBlank() && !ANY.equalsIgnoreCase(value.trim());
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean sameCity(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** One entry of the deterministic demo pool generated around the caller's GPS position. */
    private record SeedTemplate(String name, String category, String theme, List<String> tags,
                                int cost, double dLat, double dLng, String timeLabel,
                                String format, String pace) {
    }

    private record ScoredPlayer(Friend player, int score) {
    }
}
