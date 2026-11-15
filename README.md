# CrewParty

**Find real-life company in 1 minute — matched by time, budget, and energy level. Zero swiping.**

[![Track](https://img.shields.io/badge/Track-Entertainment-2563EB)]()
[![Audience](https://img.shields.io/badge/Audience-US%20Adults%2025--45-F59E0B)]()
[![Model](https://img.shields.io/badge/Model-B2C%20Web--Only-10B981)]()
[![Tests](https://img.shields.io/badge/JUnit%205%20Tests-35%20Passing-6366F1)]()

* **Live Demo (Zero Sign-Up):** `<INSERT_YOUR_DEPLOYED_PRODUCTION_URL>`
* **Target Market:** US Working Adults (25–45) — Wave 1 Launch: **Washington, D.C. · Chicago · Austin · Seattle**
* **Core Pain Solved:** Eliminates dating-app swipe fatigue (78% burnout; only 14% of matches meet in person) and expensive fixed-cost events by matching 2–4 adults around a concrete 1–2 hour slot, hard spend cap (`$0–$100`), group format (`Solo`, `Couples`, `Family`), and pace (`Chill` / `Active`) at verified public venues.

---

## 60-Second Jury Walkthrough (No Login Required)

Every user action below executes real backend logic, deterministic scoring, and live state updates — nothing is mocked or hardcoded.

### Path A: Core End-to-End Flow (Single Tab)
1. **Instant Guest Identity:** Open the live URL. A unique US 25–45 guest persona is minted automatically (`POST /api/session/init`) with zero sign-up required.
2. **Hard-Boundary Search & Map Filtering:** Open **Filters** on **Explore & Search** to filter by City (or click **Detect My City (GPS)** to dynamically seed 7 real-coordinate public safe spots + 7 open parties within ~3 km of any location), Theme, Time Window, dual-handle **Budget (`$0–$100`)**, Format (`Solo` / `Couples` / `Family`), Pace (`Chill` / `Active`), and Safety toggles (`Public Safe Spots`, `Verified Only`, `Mini-Group 3–4`).
3. **Create or Join a Party (Live AI Icebreakers + Fair Midpoint):**
   * Click **Create Party** (leave venue blank with **Auto-Midpoint Public Spot** enabled, or pick a specific venue) → the backend generates 3 theme-specific conversation starters and computes a deterministic **Match Fit Score (0–100)**.
   * Click **Join Party** on any open slot (e.g., *"2 Buddies looking for 2 more for Catan & Craft Coffee"*). When Fair-Midpoint mode is active, the backend recalculates the geographic center of all joined participants and locks the closest budget-compliant `publicSafeSpot`.
4. **Complete Hangout & Live Venue Rating (Retention Loop):**
   * Click **Complete & Rate Visit** on an active party (or open **Visit History & Ratings**, which also includes a pre-seeded unrated visit).
   * Select **1–5 stars**, tap vibe tags, and submit → the venue's `avgRating` and `ratingCount` recalculate live across map pins and the **Rated Venues** tab.
5. **B2C Monetization & Paywall:** Click **Pro / Upgrade ($9.99/mo)** in the top bar and complete the Stripe test-mode checkout using the on-screen test card (`4242 4242 4242 4242`).

### Path B: Live Multi-Tab Social & Invite Flow (2 Browser Tabs)
1. Open the live URL in **Tab 1** (assigned Persona A, e.g., *Riley Chen*) and in a second **Incognito/Private window or Tab 2** (automatically assigned Persona B from the rotating US 25–45 pool).
2. In **Tab 1**, open **Friends (Crew)** → see Tab 2 live under **Online Users Nearby** (`GET /api/presence`). Send a **Friend Request** or **Invite to My Slot**.
3. Switch to **Tab 2** → open the **Notification Bell** or **Friends (Crew)** tab and click **Accept**. Accepting a slot invite automatically adds Persona B to the party, updates player counts, and recalculates the fair-midpoint venue.

---

## Why the Core Value Is Real (Not Hardcoded & Not an LLM Wrapper)

CrewParty uses AI strictly as a host assistant while keeping matching, geospatial math, safety constraints, and retention state 100% deterministic:

1. **Deterministic Compatibility Engine (`PartyMatchService.calculateFitScore`):** Computes a transparent `0–100` score and human-readable `matchReasons` from weighted signals (Theme `+45`, City `+25`, Budget compliance `+20`, Open slots `+10`) alongside strict overlap filtering (`party.budgetMin <= budgetMax && party.budgetMax >= budgetMin`, supporting true `$0` free hangouts).
2. **Fair-Midpoint Venue Resolution:** When a party is created with `midpointMode = true`, every member join averages active participants' coordinates and selects the nearest `publicSafeSpot = true` venue whose `avgCostUsd` fits within the party's `budgetMax`.
3. **Dynamic GPS City Seeding (`POST /api/gps/seed`):** Accepts real browser coordinates and deterministically lays out 7 public venues and 7 matching open parties within ~3 km, deduplicated by city and ~100 m grid cells so judges outside the 4 Wave-1 cities can test local geo-matching anywhere.
4. **Live Multi-Tab Presence & Stale Sweep (`GET /api/presence`):** Tracks active guest sessions in a thread-safe `ConcurrentHashMap`, refreshes heartbeats, and evicts tabs inactive for >5 minutes while maintaining ambient city activity.
5. **Resilient AI Icebreakers (`AiIcebreakerService`):** Calls OpenAI Chat Completions (`gpt-4o-mini`) with a strict **6-second request timeout** (3s connect) and falls back seamlessly to curated per-theme conversation starters if `OPENAI_API_KEY` is omitted or rate-limited.

---

## Retention & B2C Business Model (Deck Appendix)

### Why Users Return Weekly & Renew Subscriptions
* **Persistent Crew Graph (`Friends (Crew)`):** Turns one-off tablemates into a reusable trusted crew for repeat weekly hangouts without awkward cold starts.
* **Post-Hangout Rating & Venue Memory (`Visit History & Ratings`):** Users log hangouts, rate public venues on table space/vibe/value, and build a personal track record of reliable spots.
* **Weekly Free & Low-Budget Event Radar (`GET /api/radar`):** Surfaces 12+ curated local free museum days, public park meetups, and neighborhood fairs (`$0` or within budget) to spark weekly plans.
* **Safety Check-In (`POST /api/parties/{id}/checkin`):** One-tap arrival check-in at verified public venues with approximate neighborhood privacy (never exposing home GPS).

### B2C Unit Economics (US Market)
* **Consumer Revenue Streams (100% B2C):**
  * **CrewParty Pro (`$7–$12/mo`, `$9.99/mo` in app):** Unlimited party slots, advanced format/pace/safety filters, and priority queue access.
  * **Single Guaranteed Slot (`$5–$10/slot`):** Pay-per-hangout option for casual users (benchmarked against Timeleft).
  * **Secondary Upside:** Optional venue affiliate perks & Eventbrite ticket commissions (supplementary only; core revenue is user-paid).
* **Target Benchmarks:** `$14` blended CAC (`$6` organic crew loops / `$22` geo-targeted ads), `$68` LTV (`4.8x` LTV/CAC over ~7 months at `$9.70` ARPU), `48%` M3 retention via weekly slot habit, `$100M` ARR target (850K paid users across top 25 US metros).

---

## Tech Stack & Architecture

| Layer | Technology | Rationale |
| :--- | :--- | :--- |
| **Backend** | Java 17, Spring Boot 3.3.5 (`spring-boot-starter-web`), Maven | Fast, strongly typed REST API with built-in static asset serving (zero CORS friction). |
| **Frontend** | Vanilla HTML5, CSS3, ES6+ JavaScript | Zero-build, instant load, mobile-adaptive responsive layout. |
| **Maps & Geo** | Leaflet 1.9 + OpenStreetMap tiles, Haversine distance & Midpoint solver | Interactive venue pins with live slot/queue badges and approximate neighborhood privacy. |
| **State & Concurrency** | Thread-safe in-memory store (`ConcurrentHashMap`, `CopyOnWriteArrayList`, `AtomicInteger`) | Zero external DB latency; defensive copies prevent concurrent session corruption. |
| **AI & Payments** | OpenAI `gpt-4o-mini` (optional, 6s timeout) + Stripe test-mode flow | Runs 100% offline/keyless out of the box while supporting live LLM generation when configured. |

### Project Structure

```text
crewparty/
├── Dockerfile                  # Multi-stage Maven + Eclipse Temurin 17 JRE Alpine image
├── pom.xml                     # Spring Boot 3.3.5 + JUnit 5 / MockMvc test suite
├── .env.example                # Optional OPENAI_API_KEY, STRIPE_SECRET_KEY, PORT
├── BACKEND_CHECKLIST.md        # Detailed DTO & frontend-backend contract audit
└── src/
    ├── main/
    │   ├── java/com/crewparty/
    │   │   ├── CrewPartyApplication.java
    │   │   ├── controller/ApiController.java          # 21 REST endpoints under /api
    │   │   ├── model/                                 # Records & domain models (Party, Venue, GuestSession, etc.)
    │   │   ├── repository/InMemoryStoreRepository.java# Thread-safe store + US 25-45 seed data
    │   │   └── service/
    │   │       ├── PartyMatchService.java             # Fit scoring, strict filters, GPS seed, midpoint, invites
    │   │       └── AiIcebreakerService.java           # gpt-4o-mini client + deterministic theme fallback
    │   └── resources/
    │       ├── application.properties
    │       └── static/                                # index.html, css/style.css, js/{api,map,app}.js
    └── test/java/com/crewparty/                       # 35 automated JUnit 5 + MockMvc tests
```

---

## Quick Start & Deployment

### Run Locally (JDK 17+ and Maven 3.9+)
```bash
cd crewparty
mvn spring-boot:run
```
Open `http://localhost:8080`. No API keys or database setup required.

### Environment Variables (All Optional)
Copy `.env.example` if you want to enable live OpenAI icebreakers:
```bash
OPENAI_API_KEY=sk-...          # Optional: enables live gpt-4o-mini icebreakers (falls back cleanly if unset)
STRIPE_SECRET_KEY=sk_test_...  # Optional: test-mode checkout works out of the box
PORT=8080                      # Optional: HTTP port (default 8080)
```

### Run via Docker
```bash
cd crewparty
docker build -t crewparty .
docker run -p 8080:8080 crewparty
```

---

## REST API Reference (21 Endpoints)

### Core End-to-End Flow
| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/api/health` | Liveness check (`{"status":"UP"}`). |
| `GET` | `/api/bootstrap` | Loads initial friends, venues, scored open parties, and visit history. |
| `GET` | `/api/search` | Deterministic search with `guestId`, `city`, `theme`, `timeWindow`, `budgetMin`, `budgetMax` (`0` = free only, `-1` = no cap), `format`, `pace`, `friendsOnly`, `publicSafeOnly`, `verifiedOnly`, `miniGroupOnly`. |
| `POST` | `/api/parties` | Creates a party (`201 Created`), generates 3 AI/themed icebreakers, supports `midpointMode`. |
| `POST` | `/api/parties/{id}/join` | Joins via `{"guestId":"..."}` or `{"friendId":"..."}` and recalculates fair-midpoint venue. |
| `POST` | `/api/parties/{id}/complete` | Marks party `COMPLETED` and logs a `VisitRecord` into Visit History. |
| `POST` | `/api/history/{visitId}/rate` | Submits 1–5 star rating + vibe tags + comment; live-recalculates venue average. |
| `POST` | `/api/friends/{id}/add` | Promotes a discovered player to `FRIEND`. |
| `POST` | `/api/checkout` | Simulates Stripe test-mode Pro unlock (`$9.99/mo`). |

### Live Sessions, GPS Seeding, Safety & Social Graph
| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/api/session/init` | Mints or restores a per-tab `GuestSession` from the rotating US 25–45 persona pool. |
| `GET` | `/api/presence?guestId=` | Heartbeat + returns `onlineGuests` (5-min stale sweep), `acceptedFriends`, and pending requests/invites. |
| `POST` | `/api/gps/seed` | Seeds 7 deduplicated `publicSafeSpot` venues & 7 open slots around `{lat, lng, cityName}`. |
| `GET` | `/api/venues/{venueId}/slots` | Returns all active `OPEN` slots and queue counts at a specific map pin. |
| `GET` | `/api/radar?city=&budgetMax=` | Returns weekly free & low-budget local events sorted cheapest-first. |
| `POST` | `/api/parties/{id}/queue` | Adds a guest to the waitlist when a party is at capacity. |
| `POST` | `/api/parties/{id}/checkin` | Records a safety arrival check-in at a public venue. |
| `POST` | `/api/friends/request` | Sends a live friend request between two active browser sessions. |
| `POST` | `/api/friends/{requestId}/accept` | Accepts a pending friend request. |
| `POST` | `/api/invites` | Sends a direct slot invite (`201 Created`) to an online user. |
| `POST` | `/api/invites/{inviteId}/respond` | Accepts or declines a slot invite (`ACCEPT` auto-joins the party). |
| `PUT` | `/api/profile` | Partially updates guest profile, default budget, format, pace, and safety toggles. |

---

## Automated Tests

Run the full JUnit 5 + Spring MockMvc test suite (35 tests, zero network dependency):
```bash
cd crewparty
mvn -B test
```
Covers deterministic `fitScore` math, strict `$0` budget filtering, two-tab persona rotation, 5-minute stale presence eviction, GPS deduplication, fair-midpoint venue locking, live venue rating recalculation, and HTTP error contracts (`400`/`404`/`409`).

---

## External APIs, Privacy & Data Disclosure

* **OpenAI Chat Completions (`gpt-4o-mini`):** Optional server-side call on party creation; no API keys are stored in the repository (loaded via `OPENAI_API_KEY` environment variable).
* **OpenStreetMap & Leaflet:** Map tiles loaded client-side from `tile.openstreetmap.org`.
* **Privacy & Demo Data:** All pre-seeded profiles, venues, parties, and ratings are fictional sample data stored strictly in server memory (resetting on restart). Location coordinates are snapped to public venues/neighborhoods—no personal PII or payment card numbers are collected or persisted.