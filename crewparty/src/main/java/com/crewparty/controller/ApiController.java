package com.crewparty.controller;

import com.crewparty.model.BootstrapResponse;
import com.crewparty.model.CreatePartyRequest;
import com.crewparty.model.Friend;
import com.crewparty.model.Party;
import com.crewparty.model.RateVisitRequest;
import com.crewparty.model.Venue;
import com.crewparty.model.VisitRecord;
import com.crewparty.repository.InMemoryStoreRepository;
import com.crewparty.service.AiIcebreakerService;
import com.crewparty.service.PartyMatchService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private static final String HOST = "You (Host)";

    private final InMemoryStoreRepository repository;
    private final PartyMatchService matchService;
    private final AiIcebreakerService icebreakerService;

    public ApiController(InMemoryStoreRepository repository,
                         PartyMatchService matchService,
                         AiIcebreakerService icebreakerService) {
        this.repository = repository;
        this.matchService = matchService;
        this.icebreakerService = icebreakerService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/bootstrap")
    public BootstrapResponse bootstrap() {
        List<Party> openParties = repository.getOpenParties();
        // No filters are applied yet, so every open party scores a full match here.
        openParties.forEach(party -> matchService.scoreParty(party, null, null, 0));

        return new BootstrapResponse(
                repository.getFriends(),
                repository.getVenues(),
                openParties,
                repository.getVisitHistory());
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam(required = false) String city,
                                      @RequestParam(required = false) String theme,
                                      @RequestParam(defaultValue = "0") int maxBudget) {
        List<Party> parties = matchService.openPartiesMatching(theme, city, maxBudget);
        List<Friend> players = matchService.playersMatching(theme, city);
        List<Venue> venues = matchService.venuesMatching(theme, city);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("parties", parties);
        response.put("players", players);
        response.put("venues", venues);
        return response;
    }

    @PostMapping("/parties")
    @ResponseStatus(HttpStatus.CREATED)
    public Party createParty(@RequestBody CreatePartyRequest request) {
        if (request.title() == null || request.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give your party a title.");
        }
        if (request.theme() == null || request.theme().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick a theme for your party.");
        }

        Venue venue = repository.findVenue(request.venueId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick a venue for your party."));

        List<String> members = new ArrayList<>();
        members.add(HOST);
        request.buddyIds().stream()
                .map(repository::findFriend)
                .flatMap(java.util.Optional::stream)
                .map(Friend::getName)
                .forEach(members::add);

        int totalSlots = Math.max(request.totalSlots(), members.size());

        Party party = new Party(
                repository.nextPartyId(),
                request.title().trim(),
                request.theme(),
                request.city() == null || request.city().isBlank() ? venue.getCity() : request.city(),
                request.neighborhood() == null || request.neighborhood().isBlank()
                        ? venue.getNeighborhood() : request.neighborhood(),
                totalSlots,
                members.size(),
                request.budgetMin(),
                Math.max(request.budgetMax(), request.budgetMin()),
                request.timeLabel() == null || request.timeLabel().isBlank() ? "This weekend" : request.timeLabel(),
                venue.getId(),
                venue.getName(),
                members,
                icebreakerService.generateIcebreakers(request.theme(), request.title(), venue.getCity()),
                Party.OPEN);

        matchService.scoreParty(party, party.getTheme(), party.getCity(), 0);
        return repository.saveParty(party);
    }

    @PostMapping("/parties/{id}/join")
    public Party joinParty(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        String friendId = body == null ? null : body.get("friendId");
        if (friendId == null || friendId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick a player to add to this party.");
        }
        return matchService.joinParty(id, friendId);
    }

    @PostMapping("/parties/{id}/complete")
    public VisitRecord completeParty(@PathVariable String id) {
        return matchService.completeParty(id);
    }

    @PostMapping("/history/{visitId}/rate")
    public VisitRecord rateVisit(@PathVariable String visitId, @RequestBody RateVisitRequest request) {
        return matchService.rateVisit(visitId, request.rating(), request.tags(), request.comment());
    }

    @PostMapping("/friends/{id}/add")
    public Friend addFriend(@PathVariable String id) {
        return matchService.addFriend(id);
    }

    @PostMapping("/checkout")
    public Map<String, Object> checkout() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "PRO_UNLOCKED");
        response.put("plan", "CrewParty Pro");
        response.put("amountUsd", 9.99);
        response.put("message", "Test-mode upgrade complete. Pro features are unlocked on this device.");
        return response;
    }
}
