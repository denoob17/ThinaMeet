package com.crewparty.repository;

import com.crewparty.model.Friend;
import com.crewparty.model.Party;
import com.crewparty.model.Venue;
import com.crewparty.model.VisitRecord;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe, in-memory store seeded with realistic US demo data.
 * Keeps its insertion order so the demo always renders the same way.
 */
@Repository
public class InMemoryStoreRepository {

    private final List<Friend> friends = new CopyOnWriteArrayList<>();
    private final List<Venue> venues = new CopyOnWriteArrayList<>();
    private final List<Party> parties = new CopyOnWriteArrayList<>();
    private final List<VisitRecord> visitHistory = new CopyOnWriteArrayList<>();

    private final AtomicInteger partySequence = new AtomicInteger(3);
    private final AtomicInteger visitSequence = new AtomicInteger(2);

    @PostConstruct
    void seedDemoData() {
        seedVenues();
        seedFriends();
        seedParties();
        seedVisitHistory();
    }

    private void seedVenues() {
        venues.add(new Venue("v1", "Snakes & Lattes Chicago", "Board Game Cafe", "Chicago", "Wicker Park",
                22, 4.7, 312, List.of("Great Table Space", "Budget Friendly", "Late Night"),
                41.9088, -87.6774));
        venues.add(new Venue("v2", "Vigilante Gastropub & Games", "Gastropub & Games", "Austin", "North Loop",
                28, 4.8, 241, List.of("Great Table Space", "Craft Drinks", "Late Night"),
                30.3165, -97.7270));
        venues.add(new Venue("v3", "Mox Boarding House Seattle", "Board Game Cafe", "Seattle", "Ballard",
                25, 4.6, 187, List.of("Great Table Space", "Huge Game Library", "Budget Friendly"),
                47.6685, -122.3845));
        venues.add(new Venue("v4", "The Board Room DC", "Board Game Bar", "Washington, D.C.", "Dupont Circle",
                26, 4.5, 154, List.of("Craft Drinks", "Quiet Enough to Talk", "Great Table Space"),
                38.9096, -77.0434));
        venues.add(new Venue("v5", "Ignite Gaming Lounge", "Esports & Gaming Lounge", "Chicago", "Avondale",
                30, 4.4, 96, List.of("Great Wi-Fi", "Late Night", "Co-op Video Games"),
                41.9389, -87.7111));
        venues.add(new Venue("v6", "Emerald Tavern Games & Cafe", "Board Game Cafe", "Austin", "North Austin",
                18, 4.7, 203, List.of("Budget Friendly", "Quiet Enough to Talk", "Great Table Space"),
                30.3835, -97.7298));
        venues.add(new Venue("v7", "Capitol CoWork & Play", "Coworking Lounge", "Washington, D.C.", "NoMa",
                20, 4.3, 88, List.of("Great Wi-Fi", "Quiet Enough to Talk", "Budget Friendly"),
                38.9067, -77.0031));
        venues.add(new Venue("v8", "Raygun Lounge", "Gaming Bar & Cafe", "Seattle", "Capitol Hill",
                15, 4.4, 121, List.of("Budget Friendly", "Quiet Enough to Talk", "Craft Drinks"),
                47.6145, -122.3185));
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
        parties.add(new Party("p1", "2 Buddies looking for 2 more for Catan & Craft Coffee",
                "Board Games", "Chicago", "Wicker Park", 4, 2, 10, 30, "Sat 3:00 PM",
                "v1", "Snakes & Lattes Chicago",
                List.of("You (Host)", "Alex Rivera"),
                List.of(
                        "What is the most chaotic Catan trade you have ever accepted?",
                        "House rule: whoever rolls a 7 buys the next round of coffee.",
                        "If you could add one resource tile to Catan, what would it be?"),
                Party.OPEN));

        parties.add(new Party("p2", "Co-op Night: Helldivers, Tacos & Craft Beer",
                "Co-op Video Games", "Austin", "North Loop", 3, 1, 15, 35, "Fri 7:00 PM",
                "v2", "Vigilante Gastropub & Games",
                List.of("Marcus Chen"),
                List.of(
                        "What is your go-to co-op loadout when the squad is short one player?",
                        "Friendly rule: no rage-quitting before the taco break.",
                        "Rank the three best couch co-op games of the last decade."),
                Party.OPEN));

        parties.add(new Party("p3", "Trivia Squad needs one ringer on Thursday",
                "Trivia & Social", "Washington, D.C.", "Dupont Circle", 4, 3, 0, 25, "Thu 8:00 PM",
                "v4", "The Board Room DC",
                List.of("Naomi Okafor", "Priya Natarajan", "You"),
                List.of(
                        "Which category would you bid everything on: music, sports or 90s TV?",
                        "Team rule: the loudest answer is never automatically the right one.",
                        "What is the most useless fact you still remember from school?"),
                Party.OPEN));
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

    public List<Friend> getFriends() {
        return new ArrayList<>(friends);
    }

    public List<Venue> getVenues() {
        return new ArrayList<>(venues);
    }

    public List<Party> getParties() {
        return new ArrayList<>(parties);
    }

    public List<Party> getOpenParties() {
        return parties.stream().filter(p -> Party.OPEN.equals(p.getStatus())).toList();
    }

    public List<VisitRecord> getVisitHistory() {
        return new ArrayList<>(visitHistory);
    }

    public Optional<Friend> findFriend(String friendId) {
        return friends.stream().filter(f -> f.getId().equals(friendId)).findFirst();
    }

    public Optional<Venue> findVenue(String venueId) {
        return venues.stream().filter(v -> v.getId().equals(venueId)).findFirst();
    }

    public Optional<Party> findParty(String partyId) {
        return parties.stream().filter(p -> p.getId().equals(partyId)).findFirst();
    }

    public Optional<VisitRecord> findVisit(String visitId) {
        return visitHistory.stream().filter(v -> v.getId().equals(visitId)).findFirst();
    }

    public Party saveParty(Party party) {
        parties.add(party);
        return party;
    }

    public VisitRecord saveVisit(VisitRecord visit) {
        visitHistory.add(visit);
        return visit;
    }

    public String nextPartyId() {
        return "p" + partySequence.incrementAndGet();
    }

    public String nextVisitId() {
        return "h" + visitSequence.incrementAndGet();
    }
}
