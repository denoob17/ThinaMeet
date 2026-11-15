package com.crewparty.controller;

import com.crewparty.model.BootstrapResponse;
import com.crewparty.model.CheckInRequest;
import com.crewparty.model.CreatePartyRequest;
import com.crewparty.model.Friend;
import com.crewparty.model.FriendRelation;
import com.crewparty.model.FriendRequestBody;
import com.crewparty.model.GpsSeedRequest;
import com.crewparty.model.GpsSeedResponse;
import com.crewparty.model.GuestSession;
import com.crewparty.model.InviteRequest;
import com.crewparty.model.InviteRespondRequest;
import com.crewparty.model.Party;
import com.crewparty.model.PresenceResponse;
import com.crewparty.model.ProfileUpdateRequest;
import com.crewparty.model.QueueRequest;
import com.crewparty.model.RadarEvent;
import com.crewparty.model.RateVisitRequest;
import com.crewparty.model.SessionInitRequest;
import com.crewparty.model.SlotInvite;
import com.crewparty.model.VenueSlotsResponse;
import com.crewparty.model.VisitRecord;
import com.crewparty.repository.InMemoryStoreRepository;
import com.crewparty.service.PartyMatchService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final InMemoryStoreRepository repository;
    private final PartyMatchService matchService;

    public ApiController(InMemoryStoreRepository repository, PartyMatchService matchService) {
        this.repository = repository;
        this.matchService = matchService;
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

    /**
     * Advanced search. Legacy params ({@code theme}, {@code maxBudget}) keep working while the new
     * budget window / format / pace / safety / friends filters are strict. {@code budgetMax} accepts
     * {@code $0} as a real value and {@code -1} means "no budget limit".
     */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam(required = false) String guestId,
                                      @RequestParam(required = false) String city,
                                      @RequestParam(required = false) String theme,
                                      @RequestParam(required = false) String timeWindow,
                                      @RequestParam(defaultValue = "0") int maxBudget,
                                      @RequestParam(defaultValue = "0") int budgetMin,
                                      @RequestParam(defaultValue = "-1") int budgetMax,
                                      @RequestParam(defaultValue = "All") String format,
                                      @RequestParam(defaultValue = "All") String pace,
                                      @RequestParam(defaultValue = "false") boolean friendsOnly,
                                      @RequestParam(defaultValue = "false") boolean publicSafeOnly,
                                      @RequestParam(defaultValue = "false") boolean verifiedOnly,
                                      @RequestParam(defaultValue = "false") boolean miniGroupOnly) {
        // maxBudget is the legacy single-value cap; only inherit it when the new window is unset.
        int effectiveBudgetMax = budgetMax >= 0 ? budgetMax : (maxBudget > 0 ? maxBudget : -1);

        PartyMatchService.SearchOutcome outcome = matchService.search(
                guestId, city, theme, timeWindow, budgetMin, effectiveBudgetMax,
                format, pace, friendsOnly, publicSafeOnly, verifiedOnly, miniGroupOnly);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("parties", outcome.parties());
        response.put("players", outcome.players());
        response.put("venues", outcome.venues());
        return response;
    }

    // ------------------------------------------------------------ live sessions

    @PostMapping("/session/init")
    public GuestSession initSession(@RequestBody(required = false) SessionInitRequest request) {
        return matchService.initSession(request);
    }

    @GetMapping("/presence")
    public PresenceResponse presence(@RequestParam(required = false) String guestId) {
        return matchService.presence(guestId);
    }

    // -------------------------------------------------------------- GPS seeding

    @PostMapping("/gps/seed")
    public GpsSeedResponse gpsSeed(@RequestBody GpsSeedRequest request) {
        return matchService.gpsSeed(request);
    }

    // -------------------------------------------------------- venues & slots

    @GetMapping("/venues/{venueId}/slots")
    public VenueSlotsResponse venueSlots(@PathVariable String venueId) {
        return matchService.venueSlots(venueId);
    }

    @GetMapping("/radar")
    public List<RadarEvent> radar(@RequestParam(required = false) String city,
                                  @RequestParam(defaultValue = "-1") int budgetMax) {
        return matchService.radar(city, budgetMax);
    }

    // ------------------------------------------------------------- parties

    @PostMapping("/parties")
    @ResponseStatus(HttpStatus.CREATED)
    public Party createParty(@RequestBody CreatePartyRequest request) {
        return matchService.createParty(request);
    }

    /** Body accepts either {@code guestId} (guest session flow) or the legacy {@code friendId}. */
    @PostMapping("/parties/{id}/join")
    public Party joinParty(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        String guestId = body == null ? null : body.get("guestId");
        String friendId = body == null ? null : body.get("friendId");
        if ((guestId == null || guestId.isBlank()) && (friendId == null || friendId.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick a player to add to this party.");
        }
        return matchService.joinParty(id, guestId, friendId);
    }

    @PostMapping("/parties/{id}/queue")
    public Party queueForSlot(@PathVariable String id, @RequestBody(required = false) QueueRequest request) {
        return matchService.queueForSlot(id, request);
    }

    @PostMapping("/parties/{id}/checkin")
    public Party checkIn(@PathVariable String id, @RequestBody CheckInRequest request) {
        return matchService.checkIn(id, request.guestId());
    }

    @PostMapping("/parties/{id}/complete")
    public VisitRecord completeParty(@PathVariable String id) {
        return matchService.completeParty(id);
    }

    // -------------------------------------------------------- history & friends

    @PostMapping("/history/{visitId}/rate")
    public VisitRecord rateVisit(@PathVariable String visitId, @RequestBody RateVisitRequest request) {
        return matchService.rateVisit(visitId, request.rating(), request.tags(), request.comment());
    }

    @PostMapping("/friends/{id}/add")
    public Friend addFriend(@PathVariable String id) {
        return matchService.addFriend(id);
    }

    @PostMapping("/friends/request")
    public FriendRelation requestFriend(@RequestBody FriendRequestBody request) {
        return matchService.requestFriend(request.fromGuestId(), request.toGuestId());
    }

    @PostMapping("/friends/{requestId}/accept")
    public FriendRelation acceptFriend(@PathVariable String requestId) {
        return matchService.acceptFriendRequest(requestId);
    }

    // --------------------------------------------------------------- invites

    @PostMapping("/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public SlotInvite createInvite(@RequestBody InviteRequest request) {
        return matchService.createInvite(request.fromGuestId(), request.toGuestId(), request.partyId());
    }

    @PostMapping("/invites/{inviteId}/respond")
    public SlotInvite respondToInvite(@PathVariable String inviteId,
                                      @RequestBody InviteRespondRequest request) {
        return matchService.respondToInvite(inviteId, request.action());
    }

    // --------------------------------------------------------------- profile

    @PutMapping("/profile")
    public GuestSession updateProfile(@RequestBody ProfileUpdateRequest request) {
        return matchService.updateProfile(request);
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
