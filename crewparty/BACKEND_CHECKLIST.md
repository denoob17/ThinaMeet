# CrewParty — Backend Checklist

Target audience: **US working adults, 25–45** (Chicago · Austin · Seattle · Washington, D.C.).
Stack: **Java 17 · Spring Boot 3.3.x · Maven · `spring-boot-starter-web` only**.

> **Boundary rule:** `src/main/resources/static/` is **read-only**. Every route, parameter and JSON
> field below was reverse-engineered from the frontend; the backend adapts to it, never the other way around.

---

## 1. Frontend–Backend Contract Audit

Source of truth: `src/main/resources/static/js/api.js` (`window.CrewApi`), consumed by `app.js`.

`api.js` builds `BASE = '/api'`, sends JSON with `Content-Type: application/json`, and on any non-2xx
reads `body.message` → `body.error` → `body.detail` → raw text → `HTTP <status>`. The backend therefore
returns error bodies from `ResponseStatusException` and `server.error.include-message=always`.

### 1.1 Every `fetch()` → controller method

| # | Frontend call (file:line) | HTTP + URL | Controller method | Request (query / path / body) | Response payload (200 unless noted) |
|---|---------------------------|-----------|-------------------|-------------------------------|-------------------------------------|
| 1 | `CrewApi.health()` — app.js:1090 | `GET /api/health` | `ApiController.health()` | — | `{"status":"UP"}` (`Map<String,String>`) |
| 2 | `CrewApi.bootstrap()` — app.js:184 | `GET /api/bootstrap` | `ApiController.bootstrap()` | — | `BootstrapResponse` |
| 3 | `CrewApi.search(filters)` — app.js:566 | `GET /api/search?city=&theme=&maxBudget=` | `ApiController.search(…)` | `@RequestParam` — legacy `city`, `theme`, `maxBudget` **plus** the new extended set (see §1.4) | `{"parties":[…],"players":[…],"venues":[…]}` |
| 4 | `CrewApi.createParty(payload)` — app.js:693 | `POST /api/parties` | `ApiController.createParty(request)` | body `CreatePartyRequest` | `Party` — **201 Created** |
| 5 | `CrewApi.joinParty(id, friendId)` — app.js:816 | `POST /api/parties/{id}/join` | `ApiController.joinParty(id, body)` | path `id`; body `{"friendId":"…"}` **or** `{"guestId":"…"}` | `Party` |
| 6 | `CrewApi.completeParty(id)` — app.js:831 | `POST /api/parties/{id}/complete` | `ApiController.completeParty(id)` | path `id` | `VisitRecord` |
| 7 | `CrewApi.rateVisit(visitId, payload)` — app.js:855 | `POST /api/history/{visitId}/rate` | `ApiController.rateVisit(visitId, request)` | path `visitId`; body `RateVisitRequest` | `VisitRecord` |
| 8 | `CrewApi.addFriend(id)` — app.js:924 | `POST /api/friends/{id}/add` | `ApiController.addFriend(id)` | path `id` | `Friend` |
| 9 | `CrewApi.checkout()` — app.js:955 | `POST /api/checkout` | `ApiController.checkout()` | — | `{"status","plan","amountUsd","message"}` |

### 1.2 DTO schemas (exact JSON property names)

**`CreatePartyRequest`** (record) — request body of #4

| JSON field | Type | Notes |
|-----------|------|-------|
| `title` | string | required (400 if blank) |
| `theme` | string | required (400 if blank) |
| `city` | string | optional → falls back to venue city |
| `neighborhood` | string | optional → falls back to venue neighborhood |
| `totalSlots` | number | clamped to ≥ current member count |
| `budgetMin` / `budgetMax` | number | `budgetMax ≥ budgetMin` enforced |
| `timeLabel` | string | optional → `"This weekend"` |
| `venueId` | string | required (400 if unknown) — **may be blank when `midpointMode = true`** |
| `buddyIds` | string[] | optional, null-safe → `[]` |
| `guestId` | string | optional — resolves the creator's `GuestSession` (name, city, coordinates, trust badges) |
| `midpointMode` | boolean | optional → `false`; `true` = "No venue selected — auto-pick midpoint" |
| `format` | string | optional → falls back to the creator's format, else `"Solo"` |
| `pace` | string | optional → falls back to the creator's pace, else `"Chill"` |
| `miniGroupPriority` | boolean | optional → `false`; prefer tight 3–4 person crews |

**`RateVisitRequest`** (record) — request body of #7

| JSON field | Type | Notes |
|-----------|------|-------|
| `rating` | number | required, **1–5** → 400 otherwise |
| `tags` | string[] | optional, null-safe → `[]` |
| `comment` | string | optional → `""` |

**`BootstrapResponse`** (record) — response of #2

| JSON field | Type (Java) | Element schema |
|-----------|-------------|----------------|
| `friends` | `List<Friend>` | `Friend` |
| `venues` | `List<Venue>` | `Venue` |
| `openParties` | `List<Party>` | `Party` (status `OPEN` only) |
| `visitHistory` | `List<VisitRecord>` | `VisitRecord` |

**`Friend`** — `id`, `name`, `ageRole`, `city`, `themes` (`string[]`), `status` (`CORE_BUDDY`/`FRIEND`/`DISCOVER`), `verified` (`boolean`).
`app.js` filters the "Players you could meet" list on `status === 'DISCOVER'`.

**`Venue`** — `id`, `name`, `category`, `city`, `neighborhood`, `avgCostUsd` (`number`), `avgRating` (`number`, 1-decimal), `ratingCount` (`number`), `tags` (`string[]`), `lat` / `lng` (`number`; `map.js` skips `0,0`), `publicSafeSpot` (`boolean`, always a real public place — never a private address), `featuredPartner` (`boolean`), `activeSlotCount` (`number`, live `OPEN` slots here), `queueCount` (`number`, guests waiting), `demoPool` (`boolean`, generated on the fly from GPS). `ratingSum` is `@JsonIgnore` (internal only).

**`Party`** — `id`, `title`, `theme`, `city`, `neighborhood`, `totalSlots`, `filledSlots`, `budgetMin`, `budgetMax`, `timeLabel`, `venueId`, `venueName`, `members` (`string[]`), `icebreakers` (`string[]`), `status`, `fitScore` (`number`, 0–100), `matchReasons` (`string[]`, additive/optional — explains `fitScore`), `midpointMode` (`boolean`), `lat` / `lng` (`number`), `memberGuestIds` (`string[]`), `memberNames` (`string[]`), `checkedInGuestIds` (`string[]`), `queueGuestNames` (`string[]`), `miniGroupPriority` (`boolean`), `trustBadges` (`string[]`), `demoPool` (`boolean`), `format` (`Solo`/`Couples`/`Family`), `pace` (`Chill`/`Active`), `budgetCompliant` (`boolean`), `distanceKm` (`number`, Haversine from the caller).

**`VisitRecord`** — `id`, `partyId`, `partyTitle`, `theme`, `venueId`, `venueName`, `visitedDate` (`YYYY-MM-DD`), `attendees` (`string[]`), `rating` (`number` or `null`), `selectedTags` (`string[]`), `reviewComment` (`string`).

### 1.3 Error contract

| Situation | Status | Message source |
|-----------|--------|----------------|
| Missing title/theme/venue on create | `400` | `ResponseStatusException(BAD_REQUEST, …)` |
| Rating outside 1–5 | `400` | `"Rating must be between 1 and 5 stars."` |
| Join without `guestId` or `friendId` | `400` | `"Pick a player to add to this party."` |
| Unknown party / visit / player id | `404` | `"… not found: <id>"` |
| Party already completed / full / duplicate member / already core buddy | `409` | conflict message |
| Party created | `201` | — |

### 1.4 Extended endpoints — matching, GPS seeding, presence, safety & profile

All routes below are **additive**: they live in `ApiController` and delegate to `PartyMatchService`.

| # | HTTP + URL | Controller method | Request | Response (200 unless noted) |
|---|-----------|-------------------|---------|-----------------------------|
| 10 | `POST /api/session/init` | `initSession(request)` | body `SessionInitRequest` | `GuestSession` |
| 11 | `GET /api/presence?guestId=` | `presence(guestId)` | query `guestId` (optional) | `PresenceResponse` |
| 12 | `POST /api/gps/seed` | `gpsSeed(request)` | body `GpsSeedRequest` | `GpsSeedResponse` |
| 13 | `GET /api/venues/{venueId}/slots` | `venueSlots(venueId)` | path `venueId` | `VenueSlotsResponse` |
| 14 | `GET /api/radar?city=&budgetMax=` | `radar(city, budgetMax)` | query `city` (optional), `budgetMax` (`-1` = no limit) | `RadarEvent[]` |
| 15 | `POST /api/parties/{id}/queue` | `queueForSlot(id, request)` | path `id`; body `QueueRequest` | `Party` |
| 16 | `POST /api/parties/{id}/checkin` | `checkIn(id, request)` | path `id`; body `CheckInRequest` | `Party` |
| 17 | `POST /api/friends/request` | `requestFriend(request)` | body `FriendRequestBody` | `FriendRelation` |
| 18 | `POST /api/friends/{requestId}/accept` | `acceptFriend(requestId)` | path `requestId` | `FriendRelation` |
| 19 | `POST /api/invites` | `createInvite(request)` | body `InviteRequest` | `SlotInvite` — **201 Created** |
| 20 | `POST /api/invites/{inviteId}/respond` | `respondToInvite(inviteId, request)` | path `inviteId`; body `InviteRespondRequest` | `SlotInvite` |
| 21 | `PUT /api/profile` | `updateProfile(request)` | body `ProfileUpdateRequest` | `GuestSession` |

#### `GET /api/search` — extended query parameters

| Param | Type | Default | Behaviour |
|-------|------|---------|-----------|
| `guestId` | string | — | Identifies the caller so `distanceKm` and `friendsOnly` can be computed. |
| `city`, `theme` | string | — | Legacy soft filters (scoring). |
| `maxBudget` | number | `0` | Legacy single cap; inherited as `budgetMax` only when the new window is unset. |
| `timeWindow` | string | — | Soft preference; adds a `matchReasons` line, never hides a result. |
| `budgetMin` / `budgetMax` | number | `0` / `-1` | Strict budget window when `budgetMax ≥ 0`. **`budgetMax=0` means a real `$0` limit**; `-1` = no limit. Keeps parties where `party.budgetMin ≤ budgetMax && party.budgetMax ≥ budgetMin`. |
| `format` | string | `All` | `All`/`Solo`/`Couples`/`Family` (strict). |
| `pace` | string | `All` | `All`/`Chill`/`Active` (strict). |
| `friendsOnly` | boolean | `false` | Only parties with a `memberGuestIds` entry in the caller's `ACCEPTED` friends. |
| `publicSafeOnly` | boolean | `false` | Only parties whose venue has `publicSafeSpot = true`. |
| `verifiedOnly` | boolean | `false` | Only parties that carry at least one `trustBadge`. |
| `miniGroupOnly` | boolean | `false` | Only parties with `miniGroupPriority = true`. |

Response adds `budgetCompliant` (`boolean`) and `distanceKm` (`number`) to every `Party`; `fitScore` / `matchReasons` are still computed. When no strict filter is active the legacy `fitScore ≥ 65` threshold applies unchanged.

#### New DTO schemas

**`SessionInitRequest`** — body of #10 · **`GpsSeedRequest`** — body of #12

| JSON field | Type | Notes |
|-----------|------|-------|
| `guestId` | string | #10 only, optional — unknown ids get a fresh persona under that id. |
| `city` | string | #10 only, optional — overrides the persona's city. |
| `lat` / `lng` | number | #12 only — required coordinates. |
| `cityName` | string | #12 only, optional — falls back to the closest seeded city, else `"Your Area"`. |

**`PresenceResponse`** — response of #11

| JSON field | Type (Java) | Notes |
|-----------|-------------|-------|
| `currentGuest` | `GuestSession` or `null` | Caller's session (heartbeat refreshed; `null` if unknown). |
| `onlineGuests` | `List<GuestSession>` | Every live tab, most recently seen first; sessions older than 5 min are dropped (ambient demo guests are refreshed, never dropped). |
| `acceptedFriends` | `List<FriendRelation>` | `status = ACCEPTED` relations touching the caller. |
| `pendingFriendRequests` | `List<FriendRelation>` | `status = PENDING` requests **addressed to** the caller. |
| `pendingSlotInvites` | `List<SlotInvite>` | `status = PENDING` invites **addressed to** the caller. |

**`GuestSession`** — `guestId`, `displayName`, `ageRole` (e.g. `"28 • UX Designer"`), `city`, `neighborhood`, `lat` / `lng`, `format` (`Solo`/`Couples`/`Family`), `pace` (`Chill`/`Active`), `budgetMin`, `budgetMax`, `verificationBadges` (`string[]`: `ID Verified`/`LinkedIn`/`Shared Interests`), `publicVenuesOnly` (`boolean`), `miniGroupPriority` (`boolean`), `lastSeenEpochMs` (`number`).

**`FriendRelation`** — `id`, `fromGuestId`, `fromName`, `toGuestId`, `toName`, `status` (`PENDING`/`ACCEPTED`).

**`SlotInvite`** — `id`, `fromGuestId`, `fromName`, `toGuestId`, `partyId`, `partyTitle`, `venueName`, `status` (`PENDING`/`ACCEPTED`/`DECLINED`), `createdAtMs` (`number`).

**`RadarEvent`** — `id`, `title`, `city`, `category` (`Free Museum Day`/`Local Fair`/`Public Park Meetup`), `costPerPerson` (`number`), `whenLabel`, `venueName`, `lat` / `lng`, `publicSafeSpot` (`boolean`).

**`VenueSlotsResponse`** — `venue` (`Venue`), `slots` (`Party[]`, all `OPEN` slots at that venue), `activeSlotCount` (`number`), `queueCount` (`number`).

**`GpsSeedResponse`** — `city`, `centerLat`, `centerLng`, `venuesCreated` (`number`), `partiesCreated` (`number`), `venues` (`Venue[]`), `parties` (`Party[]`), `demoPool` (`true`).

**Request bodies** — `QueueRequest` `{ guestId, guestName }`; `CheckInRequest` `{ guestId }`; `FriendRequestBody` `{ fromGuestId, toGuestId }`; `InviteRequest` `{ fromGuestId, toGuestId, partyId }`; `InviteRespondRequest` `{ action: "ACCEPT" | "DECLINE" }`; `ProfileUpdateRequest` `{ guestId, displayName?, ageRole?, city?, neighborhood?, budgetMin?, budgetMax?, format?, pace?, publicVenuesOnly?, miniGroupPriority?, verificationBadges? }` (every field except `guestId` is optional — `null` means "leave as is").

#### Extended error contract

| Situation | Status | Message source |
|-----------|--------|----------------|
| `/session/presence/profile/invite/friend` without a `guestId` | `400` | `"A guestId is required."` |
| Unknown guest session / venue / invite / friend request | `404` | `"… not found: <id>"` |
| Friend request to yourself | `400` | `"You cannot add yourself."` |
| Duplicate or already-answered friend request / invite | `409` | conflict message |
| Join a full party as a guest | `409` | `"This party is already full. Join the queue instead."` |
| `respond` with an action other than `ACCEPT`/`DECLINE` | `400` | `"Action must be ACCEPT or DECLINE."` |

---

## 2. Core Business Logic Checklist

- [x] **Thread-safe in-memory store** — `InMemoryStoreRepository` uses `CopyOnWriteArrayList` + `AtomicInteger` id sequences, seeded in `@PostConstruct` with US 25–45 demo data (4 cities, 8 venues with real coordinates/prices/ratings, 7 adult profiles, 3 open parties, visit history incl. **one unrated visit**). Reads return defensive copies so concurrent browser sessions cannot corrupt state.
- [x] **Deterministic Search & Match Scoring** — `PartyMatchService.calculateFitScore(...)` returns a `FitResult(int score, List<String> reasons)`; score 0–100 is **computed** from weighted city + theme + budget compliance + open slots (never hardcoded). `/api/search` filters to `≥ MIN_MATCH_SCORE (65)` and sorts descending; `/api/bootstrap` scores every open party so the "Fit Score" bar is correct on first paint.
- [x] **Create Party flow** — `POST /api/parties` validates input, resolves the venue, links the selected `buddyIds`, generates **3 theme-tailored icebreakers**, clamps `totalSlots`, scores the new party, adds it to the open parties and returns **201**.
- [x] **Join Party & Friends flow** — `POST /api/parties/{id}/join` appends the member and refreshes `filledSlots` (409 on full/completed/duplicate); `POST /api/friends/{id}/add` promotes a player to `status = "FRIEND"` (409 if already a core buddy).
- [x] **Complete Hangout → Visit History → Venue Rating** — `POST /api/parties/{id}/complete` flips the party to `COMPLETED` and appends a `VisitRecord` (`rating = null`, today's date). `POST /api/history/{visitId}/rate` persists 1–5 stars + vibe tags + comment and **live-recalculates** the venue via `Math.round((ratingSum / ratingCount) * 10.0) / 10.0`, incrementing `ratingCount` on first rating and never double-counting on re-rating.
- [x] **Live AI Icebreaker Generation** — `AiIcebreakerService` calls OpenAI Chat Completions (`gpt-4o-mini`) over `java.net.http.HttpClient` with a **6-second request timeout** (3-second connect) so the UI never hangs, parses the JSON array with Jackson, and falls back to a deterministic **theme-aware** list when `OPENAI_API_KEY` is blank or the call fails.
- [x] **Stripe Test Checkout** — `POST /api/checkout` returns an instant test-mode confirmation (`status`, `plan`, `amountUsd`, `message`) matching the frontend paywall flow; secrets are read from env with empty defaults (`${STRIPE_SECRET_KEY:}`), nothing is hardcoded.

### 2.1 Extended Business Logic (matching · GPS · presence · safety · profile)

- [x] **1 · Multi-Tab Live Guest Sessions (zero login)** — `POST /api/session/init` mints a `GuestSession` per tab. An unknown/blank `guestId` pulls the next persona from an 8-entry rotating pool of **US 25–45** profiles (`ConcurrentHashMap` store + `AtomicInteger` rotation via `Math.floorMod`), so two open tabs automatically become two different people without any account. `GET /api/presence?guestId=` refreshes `lastSeenEpochMs`, **drops sessions staler than 5 minutes** (`cleanStaleSessions`), and returns `{ currentGuest, onlineGuests, acceptedFriends, pendingFriendRequests, pendingSlotInvites }`. Three ambient demo guests are re-touched instead of aged out so presence is never empty.
- [x] **2 · GPS City Detection & Dynamic Slot Generation** — `POST /api/gps/seed` accepts `{ lat, lng, cityName }` and deterministically lays out **7 public venues + 7 matching open slots** within ~3 km (fixed offsets, categories `Board Games` / `Coworking & Chill` / `Active / Sports` / `Coffee & Trivia`), all flagged `demoPool = true` and `publicSafeSpot = true`. Deduplicated by city + rounded (~100 m) coordinates, so repeated calls return the existing pool instead of flooding memory.
- [x] **3 · Venue Multi-Slot List & Queue** — `GET /api/venues/{venueId}/slots` returns the venue plus every `OPEN` slot hosted there (`activeSlotCount` / `queueCount` recomputed live), so tapping a map pin lists all active slots. `POST /api/parties/{id}/queue` appends `{ guestId, guestName }` to `queueGuestNames` **only when the slot is at capacity** (`filledSlots ≥ totalSlots`).
- [x] **4 · Optional Fair-Midpoint Venue Calculation** — `POST /api/parties` with `midpointMode = true` (venue left unselected) creates the party at the creator's coordinates with `venueName = "TBD • Auto-Midpoint Public Spot"`. Every subsequent `POST /api/parties/{id}/join` averages the joined members' coordinates and **auto-selects the closest `publicSafeSpot = true` venue with `avgCostUsd ≤ party.budgetMax`**, updating `venueId` / `venueName` / `lat` / `lng` / `distanceKm`. When `midpointMode = false`, `distanceKm` is the exact Haversine distance to the chosen venue.
- [x] **5 · Advanced Deterministic Search & Compatibility Scoring** — `GET /api/search` supports `guestId, city, timeWindow, budgetMin, budgetMax, format, pace, friendsOnly, publicSafeOnly, verifiedOnly, miniGroupOnly` alongside the legacy `theme` / `maxBudget`. Budget is a strict overlap test (`party.budgetMin ≤ budgetMax && party.budgetMax ≥ budgetMin`, `$0` supported); `friendsOnly` keeps parties with a `memberGuestIds` entry in the caller's `ACCEPTED` friends. Every result carries a deterministic `fitScore` (0–100) + `budgetCompliant` + `distanceKm`.
- [x] **6 · Weekly Free & Low-Budget Event Radar** — `GET /api/radar?city=&budgetMax=` returns curated upcoming free museum days, local fairs and public park meetups (`costPerPerson == 0` or `≤ budgetMax`), cheapest first. 12 seeded events across the four demo cities.
- [x] **7 · Live Friend Requests, Slot Invites, Safety Check-In & Profile** — `POST /api/friends/request` + `POST /api/friends/{requestId}/accept`; `POST /api/invites` + `POST /api/invites/{inviteId}/respond` (**`ACCEPT` drops the guest straight into the target party** and recomputes the midpoint); `POST /api/parties/{id}/checkin` records the safety check-in; `PUT /api/profile` patches name, `ageRole`, city, neighborhood, default budget, format, pace and the safety/verification toggles (every field optional).
- [x] **Thread safety** — all new state lives in `ConcurrentHashMap` (`guestSessions`) and `CopyOnWriteArrayList` (`friendRelations`, `slotInvites`, `radarEvents`), with `AtomicInteger` id/rotation sequences; every new write path ends with `recountVenueLoad()`. No secrets are hardcoded (OpenAI/Stripe keys stay env-driven).

---

## 3. Verification & Smoke Tests

### 3.1 Compile

```bash
cd crewparty
mvn -q compile          # no Maven wrapper in this repo, so `mvn`, not `./mvnw`
```

Expected: `BUILD SUCCESS`, zero compilation errors/warnings.

### 3.1.1 Automated Java test suite

```bash
cd crewparty
mvn -B test             # runs the whole JUnit 5 suite
```

Expected: `BUILD SUCCESS` — **35 tests, 0 failures, 0 errors**, no network access required
(the AI icebreaker is exercised with an empty API key, so it always takes the offline fallback).

`spring-boot-starter-test` was added to `pom.xml` (test scope) to provide JUnit 5 + AssertJ + MockMvc.

| Test class | Package | Covers |
|-----------|---------|--------|
| `InMemoryStoreRepositoryTest` | `com.crewparty.repository` | demo seed baseline (8 venues / 7 friends / 3 open parties / 12 radar events / one unrated visit), GPS dedupe by city + ~100 m cell, `cleanStaleSessions` (drops old tabs, refreshes the ambient crowd), `recountVenueLoad`, and the defensive-copy read contract. |
| `PartyMatchServiceTest` | `com.crewparty.service` | deterministic `fitScore` + reasons, strict budget window incl. a real **`$0`** cap, strict format/pace filters, `friendsOnly`, two-tab `session/init` personas, presence heartbeat + stale sweep, GPS seeding (7 public demo venues within ~3 km, deduplicated on repeat), venue multi-slot list, capacity-only queue, explicit-venue vs. midpoint venue resolution, free/low-budget radar, friend request lifecycle, invite accept/decline, safety check-in, partial profile update, create/join guards, and the live venue-average recalculation on rating. |
| `ApiControllerTest` | `com.crewparty.controller` | HTTP contract via MockMvc: `/api/health`, `/api/bootstrap` collections, `POST /api/parties` → `201` + icebreakers, `session/init` + `presence` round-trip, extended `/api/search` params, `/api/gps/seed`, `404` for an unknown venue, `400` for a profile update without a `guestId`. |


### 3.2 curl smoke tests

> PowerShell note: use `curl.exe` (the bare `curl` alias maps to `Invoke-WebRequest` and won't accept `-X`/`-d`).
> The server must be running (`mvn spring-boot:run`, default port `8080`).

```bash
# 1) health
curl -s http://localhost:8080/api/health
# -> {"status":"UP"}

# 2) bootstrap (friends + venues + open parties + visit history)
curl -s http://localhost:8080/api/bootstrap

# 3) search with filters (city + theme + budget)
curl -s "http://localhost:8080/api/search?city=Chicago&theme=Board%20Games&maxBudget=30"

# 4) create a party (expect 201; icebreakers auto-generated)
curl -s -i -X POST http://localhost:8080/api/parties \
  -H "Content-Type: application/json" \
  -d '{"title":"Friday Catan Crew","theme":"Board Games","city":"Chicago","neighborhood":"Wicker Park","totalSlots":4,"budgetMin":10,"budgetMax":30,"timeLabel":"Fri 7:00 PM","venueId":"v1","buddyIds":["f2"]}'

# 5) join an open party (adds a player, refreshes slot counts)
curl -s -X POST http://localhost:8080/api/parties/p1/join \
  -H "Content-Type: application/json" \
  -d '{"friendId":"f3"}'

# 6) complete a party (creates a VisitRecord with rating = null)
curl -s -X POST http://localhost:8080/api/parties/p2/complete

# 7) rate a visit (1–5 stars + tags; recalculates the venue live)
curl -s -X POST http://localhost:8080/api/history/h2/rate \
  -H "Content-Type: application/json" \
  -d '{"rating":5,"tags":["Great Wi-Fi","Quiet Enough to Talk"],"comment":"Perfect focus spot."}'

# 8) add a player as a friend (status -> FRIEND)
curl -s -X POST http://localhost:8080/api/friends/f5/add

# 9) Stripe test checkout
curl -s -X POST http://localhost:8080/api/checkout
# -> {"status":"PRO_UNLOCKED","plan":"CrewParty Pro","amountUsd":9.99,"message":"…"}

# 10) error paths: must return 400 / 404 with a readable message
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/history/h1/rate \
  -H "Content-Type: application/json" -d '{"rating":9,"tags":[],"comment":""}'   # -> 400
curl -s -X POST http://localhost:8080/api/parties/p1/join \
  -H "Content-Type: application/json" -d '{"friendId":"nope"}'                   # -> 404 "Player not found: nope"

# --- Extended endpoints (sessions · presence · GPS · slots · radar · invites · profile) ---

# 11) open two tabs: two init calls with no guestId -> two different personas
curl -s -X POST http://localhost:8080/api/session/init -H "Content-Type: application/json" -d '{}'
curl -s -X POST http://localhost:8080/api/session/init -H "Content-Type: application/json" -d '{"city":"Chicago"}'

# 12) presence heartbeat for one tab (returns currentGuest + onlineGuests + pending lists)
curl -s "http://localhost:8080/api/presence?guestId=g1"

# 13) GPS seed around downtown Chicago (first call creates, repeat call returns the same pool)
curl -s -X POST http://localhost:8080/api/gps/seed \
  -H "Content-Type: application/json" -d '{"lat":41.8781,"lng":-87.6298,"cityName":"Chicago"}'

# 14) every OPEN slot at one venue (tap a map pin)
curl -s http://localhost:8080/api/venues/v1/slots

# 15) weekly free & low-budget radar for one city (budgetMax=0 keeps only free events)
curl -s "http://localhost:8080/api/radar?city=Austin&budgetMax=0"

# 16) extended search: $0-capable budget window + strict filters
curl -s "http://localhost:8080/api/search?city=Chicago&budgetMin=0&budgetMax=30&format=Solo&pace=Chill&publicSafeOnly=true&verifiedOnly=true&miniGroupOnly=true"

# 17) friend request -> accept (use two real guestIds from step 11)
curl -s -X POST http://localhost:8080/api/friends/request \
  -H "Content-Type: application/json" -d '{"fromGuestId":"g1","toGuestId":"g2"}'
curl -s -X POST http://localhost:8080/api/friends/fr1/accept

# 18) slot invite -> accept (target guest is added to the party automatically)
curl -s -X POST http://localhost:8080/api/invites \
  -H "Content-Type: application/json" -d '{"fromGuestId":"g1","toGuestId":"g2","partyId":"p1"}'
curl -s -X POST http://localhost:8080/api/invites/si1/respond \
  -H "Content-Type: application/json" -d '{"action":"ACCEPT"}'

# 19) guest join (feeds the midpoint) + safety check-in
curl -s -X POST http://localhost:8080/api/parties/p1/join \
  -H "Content-Type: application/json" -d '{"guestId":"g2"}'
curl -s -X POST http://localhost:8080/api/parties/p1/checkin \
  -H "Content-Type: application/json" -d '{"guestId":"g2"}'

# 20) queue when a slot is full (no-op while slots remain)
curl -s -X POST http://localhost:8080/api/parties/p3/queue \
  -H "Content-Type: application/json" -d '{"guestId":"g3","guestName":"Riley Chen"}'

# 21) profile settings (partial update — only the provided fields change)
curl -s -X PUT http://localhost:8080/api/profile \
  -H "Content-Type: application/json" \
  -d '{"guestId":"g1","displayName":"Jordan Vance","city":"Chicago","neighborhood":"Logan Square","budgetMin":10,"budgetMax":45,"format":"Solo","pace":"Chill","publicVenuesOnly":true,"miniGroupPriority":true}'

# 22) extended error paths
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/invites/si1/respond \
  -H "Content-Type: application/json" -d '{"action":"MAYBE"}'                    # -> 400
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8080/api/venues/nope/slots"   # -> 404
```

### 3.3 Static boundary check (must be completely empty)

```bash
git status --short src/main/resources/static/
git diff --name-only src/main/resources/static/
```

Expected: **no output** — zero frontend files were created, edited, renamed or deleted.

---

## 4. Notes for the Frontend Developer

- **No mock flag exists.** `api.js` calls the real API directly — there is no `USE_MOCK` to flip; the backend serves `/api` on the same origin, so no CORS/URL change is needed.
- **`fitScore` is now populated on first paint.** `/api/bootstrap` scores every open party, so the "Fit Score" bar renders a real 0–100 value instead of `0%`.
- **New additive field `matchReasons`** (`string[]`) on `Party` — a human-readable explanation of `fitScore` (e.g. `"Theme matches \"Board Games\" (+45)"`). Fully optional; render it if you want a "Why this matches" tooltip. Nothing existing changed.
- **`POST /api/parties` now answers `201 Created`.** `api.js` only checks `response.ok`, so no change is required.
- **Error toasts already work** thanks to `server.error.include-message=always`; the backend returns `message`-bearing bodies for every 400/404/409.
- **Live AI is opt-in.** Set `OPENAI_API_KEY` before `mvn spring-boot:run` to get live `gpt-4o-mini` icebreakers; without it the deterministic themed fallback is used (still 3 relevant starters, 6s timeout guarantee on the live path).

### 4.1 Matching / GPS / presence / safety release (all additive)

- **Nothing existing broke.** Every previously documented route keeps the same URL, body and status code. `/api/parties` still returns `201`; `/api/parties/{id}/join` still accepts `{"friendId": "…"}` and now *also* accepts `{"guestId": "…"}`.
- **`/api/search` is backwards compatible.** The old `city` / `theme` / `maxBudget` params behave exactly as before (score-based, `fitScore ≥ 65`). Only when you send one of the new params (`budgetMin`/`budgetMax`, `format`, `pace`, `friendsOnly`, `publicSafeOnly`, `verifiedOnly`, `miniGroupOnly`) does strict filtering kick in. `budgetMax=0` is a real **$0** limit; omit it (or pass `-1`) for "no budget limit".
- **`Party` gained optional fields** — `distanceKm`, `budgetCompliant`, `format`, `pace`, `midpointMode`, `lat`/`lng`, `memberGuestIds`, `memberNames`, `checkedInGuestIds`, `queueGuestNames`, `miniGroupPriority`, `trustBadges`, `demoPool`. Render only what you need; all are additive.
- **`Venue` gained optional fields** — `publicSafeSpot`, `featuredPartner`, `activeSlotCount`, `queueCount`, `demoPool`. Use `activeSlotCount`/`queueCount` for the pin badge; `demoPool = true` marks GPS-generated places.
- **Zero-login identity.** Call `POST /api/session/init` once per tab (no body is fine). You get back a `GuestSession` with a `guestId` — store it in `sessionStorage` so each tab keeps its own identity. Poll `GET /api/presence?guestId=…` for the live "who's online + invites" panel; drop tabs whose `lastSeenEpochMs` goes stale.
- **GPS map seeding.** After a successful `navigator.geolocation` fix, call `POST /api/gps/seed` with `{ lat, lng, cityName }` and plot the returned `venues` / `parties`. Repeating the call for the same area is safe (it returns the existing pool, `venuesCreated = 0`).
- **Tap a pin → `GET /api/venues/{venueId}/slots`** for the full "all active slots here" list, and `POST /api/parties/{id}/queue` (body `{ guestId, guestName }`) when a slot is full.
- **Midpoint parties.** If you send `midpointMode: true` with no `venueId`, the party is created with `venueName = "TBD • Auto-Midpoint Public Spot"` and the backend locks the real public spot the moment someone joins — just re-render `venueName` / `lat` / `lng` from the join response (or refresh the party).
- **Radar.** `GET /api/radar?city=…&budgetMax=…` returns the weekly free/low-budget list; `budgetMax=0` shows only truly free events.
