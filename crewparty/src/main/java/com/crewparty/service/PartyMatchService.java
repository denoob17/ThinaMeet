package com.crewparty.service;

import com.crewparty.model.Friend;
import com.crewparty.model.Party;
import com.crewparty.model.Venue;
import com.crewparty.model.VisitRecord;
import com.crewparty.repository.InMemoryStoreRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic matching, plus the live recalculation of venue ratings.
 */
@Service
public class PartyMatchService {

    /** Themes that mean "no filter" when they arrive from the UI. */
    private static final String ANY_THEME = "All Themes";
    private static final String ANY_CITY = "All Cities";

    private static final int THEME_WEIGHT = 45;
    private static final int CITY_WEIGHT = 25;
    private static final int BUDGET_WEIGHT = 20;
    private static final int SLOTS_WEIGHT = 10;

    /** Results below this fit score are hidden: with active filters they simply do not match. */
    private static final int MIN_MATCH_SCORE = 65;

    /** Keywords used to guess whether a venue fits a theme. */
    private static final List<String> GAMES_KEYWORDS =
            List.of("game", "board", "gaming", "esports", "cafe");
    private static final List<String> COWORK_KEYWORDS =
            List.of("cowork", "co-work", "lounge", "quiet", "wifi", "wi-fi");
    private static final List<String> BAR_KEYWORDS =
            List.of("bar", "gastropub", "pub", "tavern", "trivia");

    private final InMemoryStoreRepository repository;

    public PartyMatchService(InMemoryStoreRepository repository) {
        this.repository = repository;
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

    public List<Party> openPartiesMatching(String theme, String city, int maxBudget) {
        return repository.getOpenParties().stream()
                .map(party -> scoreParty(party, theme, city, maxBudget))
                .filter(party -> party.getFitScore() >= MIN_MATCH_SCORE)
                .sorted(Comparator.comparingInt(Party::getFitScore).reversed())
                .toList();
    }

    public List<Friend> playersMatching(String theme, String city) {
        return repository.getFriends().stream()
                .filter(player -> !Friend.CORE_BUDDY.equals(player.getStatus()))
                .map(player -> new ScoredPlayer(player, calculatePlayerFitScore(player, theme, city)))
                .filter(scored -> scored.score() >= MIN_MATCH_SCORE)
                .sorted(Comparator.comparingInt(ScoredPlayer::score).reversed())
                .map(ScoredPlayer::player)
                .toList();
    }

    public List<Venue> venuesMatching(String theme, String city) {
        return repository.getVenues().stream()
                .filter(venue -> matchesVenueFilters(venue, city))
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

    // ---------------------------------------------------- party state changes

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

        return repository.saveVisit(record);
    }

    public Party joinParty(String partyId, String friendId) {
        Party party = repository.findParty(partyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Party not found: " + partyId));

        if (Party.COMPLETED.equals(party.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This party has already happened.");
        }
        if (party.getOpenSlots() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This party is already full.");
        }

        Friend friend = repository.findFriend(friendId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found: " + friendId));

        if (party.getMembers().contains(friend.getName())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, friend.getName() + " is already in this party.");
        }

        party.getMembers().add(friend.getName());
        party.setFilledSlots(party.getMembers().size());
        return party;
    }

    // ------------------------------------------------------- rating & friends

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

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private record ScoredPlayer(Friend player, int score) {
    }
}
