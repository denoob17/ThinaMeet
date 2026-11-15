package com.crewparty.repository;

import com.crewparty.model.GuestSession;
import com.crewparty.model.Party;
import com.crewparty.model.Venue;
import com.crewparty.model.VisitRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the thread-safe in-memory store: the demo seed, the GPS dedupe guard, the stale-session
 * sweep and the defensive-copy contract every concurrent browser tab relies on.
 */
class InMemoryStoreRepositoryTest {

    private InMemoryStoreRepository seeded() {
        InMemoryStoreRepository repository = new InMemoryStoreRepository();
        repository.seedDemoData();
        return repository;
    }

    @Test
    void seedDemoDataCreatesTheDocumentedBaseline() {
        InMemoryStoreRepository repository = seeded();

        assertThat(repository.getVenues()).hasSize(8);
        assertThat(repository.getFriends()).hasSize(7);
        assertThat(repository.getOpenParties()).hasSize(3);
        assertThat(repository.getRadarEvents()).hasSize(12);

        List<VisitRecord> history = repository.getVisitHistory();
        assertThat(history).hasSize(2);
        // Exactly one seeded visit is still unrated so the "Complete & Rate" demo has work to do.
        assertThat(history).filteredOn(visit -> visit.getRating() == null).hasSize(1);
    }

    @Test
    void seededVenuesKeepTheirFeaturedAndSafetyFlags() {
        InMemoryStoreRepository repository = seeded();

        assertThat(repository.findVenue("v1")).isPresent();
        Venue featured = repository.findVenue("v1").orElseThrow();
        assertThat(featured.isPublicSafeSpot()).isTrue();
        assertThat(featured.isFeaturedPartner()).isTrue();
        assertThat(featured.isDemoPool()).isFalse();
    }

    @Test
    void recountVenueLoadCountsOpenSlotsAndQueuedGuests() {
        InMemoryStoreRepository repository = seeded();
        repository.recountVenueLoad();

        // p1 is an OPEN slot hosted at v1.
        Venue v1 = repository.findVenue("v1").orElseThrow();
        assertThat(v1.getActiveSlotCount()).isEqualTo(1);

        Party party = repository.findParty("p1").orElseThrow();
        party.setQueueGuestNames(List.of("Quinn Alvarez"));
        repository.recountVenueLoad();
        assertThat(repository.findVenue("v1").orElseThrow().getQueueCount()).isEqualTo(1);
    }

    @Test
    void cleanStaleSessionsDropsOldTabsButRefreshesAmbientGuests() {
        InMemoryStoreRepository repository = seeded();

        GuestSession stale = new GuestSession("stale-tab", "Old Tab", "30 • Tester", "Chicago",
                "Downtown", 41.9, -87.6, GuestSession.SOLO, GuestSession.CHILL, 0, 30, List.of());
        stale.setLastSeenEpochMs(1L);
        repository.saveGuest(stale);

        int removed = repository.cleanStaleSessions(System.currentTimeMillis());

        assertThat(removed).isEqualTo(1);
        assertThat(repository.findGuest("stale-tab")).isEmpty();
        // The three ambient demo guests survive so a single-tab demo still shows a living presence list.
        assertThat(repository.findGuest("gs1")).isPresent();
        assertThat(repository.findGuest("gs2")).isPresent();
        assertThat(repository.findGuest("gs3")).isPresent();
    }

    @Test
    void gpsSeedingIsDeduplicatedWithinFifteenKilometres() {
        InMemoryStoreRepository repository = seeded();

        // The seeded venues are real places, not a generated demo pool, so nothing is deduplicated yet.
        assertThat(repository.hasVenueNear("Chicago", 41.8781, -87.6298)).isFalse();

        Venue pool = new Venue("vDemo", "North Loop Board Game Cafe", "Board Game Cafe", "Chicago",
                "Near You", 18, 4.5, 20, List.of(), 41.8781, -87.6298);
        pool.setDemoPool(true);
        repository.saveVenue(pool);

        assertThat(repository.hasVenueNear("Chicago", 41.8781, -87.6298)).isTrue();
        // ~13.5 km away in the same metro -> still shares the pool instead of duplicating it.
        assertThat(repository.hasVenueNear("Chicago", 41.9999, -87.6298)).isTrue();
        // Well outside the 15 km radius -> a fresh pool is allowed.
        assertThat(repository.hasVenueNear("Chicago", 30.2672, -97.7431)).isFalse();
    }

    @Test
    void readsReturnDefensiveCopiesSoTabsCannotCorruptState() {
        InMemoryStoreRepository repository = seeded();

        List<Venue> copy = repository.getVenues();
        copy.clear();

        assertThat(repository.getVenues()).hasSize(8);
    }
}
