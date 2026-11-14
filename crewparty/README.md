# CrewParty

**Find your crew. Pick the spot. Rate the hangout.**

CrewParty is a B2C web app for US working adults (25-45) who already have a buddy but need 2-3 more
people to make a hangout happen. No sign-up, no password: open the page and you are inside the product.

Demo scenario: two friends in Chicago want to play Catan this Saturday. They create a party, pick a
theme, find matching players and a public venue, meet up, then log the visit, rate the venue 1-5 stars
and save the people they met as friends.

---

## Tech stack

| Layer    | Choice                                                        |
|----------|---------------------------------------------------------------|
| Backend  | Java 17, Spring Boot 3.3, Maven (`spring-boot-starter-web`)    |
| Frontend | Vanilla HTML5 + CSS3 + ES6+ JavaScript, served from `static/`  |
| Map      | Leaflet 1.9 + OpenStreetMap tiles (CDN)                       |
| Storage  | In-memory, thread-safe, seeded with US demo data               |

No database, no login, no build step for the frontend. One `mvn spring-boot:run` and everything works
offline.

---

## Getting started

Requirements: **JDK 17+** and **Maven 3.9+**.

```bash
cd crewparty
mvn spring-boot:run
```

Then open <http://localhost:8080>.

Optional environment variables (see `.env.example`) — the app runs fine without them:

```bash
OPENAI_API_KEY=sk-...      # enables real AI icebreakers (gpt-4o-mini)
STRIPE_SECRET_KEY=sk_test_...  # reserved for the Stripe test-mode upgrade flow
PORT=8080
```

Build a runnable jar or container:

```bash
mvn clean package
java -jar target/crewparty-0.1.0.jar

docker build -t crewparty .
docker run -p 8080:8080 crewparty
```

---

## 1-minute jury walkthrough

1. **Open** <http://localhost:8080> — the dashboard loads instantly, no login.
2. Click **"1-Click Demo: 2 Buddies Seeking 2 Players"** — it publishes a filled demo party instantly
   (AI icebreakers included).
3. On **Explore & Search**, click **Join Party** on the Catan party and add a suggested player.
4. Click **Complete & Rate Visit** on that same party — it moves straight into **Visit History**.
5. Open the **Visit History & Ratings** tab, pick **1-5 stars**, tap a few vibe tags, write a comment.
6. Click **Submit Venue Rating** — the venue's live average updates everywhere, including the map popups
   and the **Rated Venues** tab.
7. Use **+ Add to Friends** on a person who attended, then check the **Friends (Crew)** tab.
8. Optionally click **Upgrade ($9.99/mo)** and pay with the Stripe test card `4242 4242 4242 4242`.

A pre-seeded unrated visit is already in **Visit History**, so step 5 works even without creating a party.

---

## API

| Method | Path                        | Purpose                                                        |
|--------|-----------------------------|----------------------------------------------------------------|
| GET    | `/api/health`               | Liveness probe: `{"status":"UP"}`                              |
| GET    | `/api/bootstrap`            | Friends, venues, open parties and visit history in one call     |
| GET    | `/api/search`               | `?city=&theme=&maxBudget=` → parties + players + venues         |
| POST   | `/api/parties`              | Create a party (generates AI icebreakers)                      |
| POST   | `/api/parties/{id}/join`    | Add a player: body `{"friendId":"f3"}`                         |
| POST   | `/api/parties/{id}/complete`| Move the party into Visit History                               |
| POST   | `/api/history/{id}/rate`    | Rate a visit: `{"rating":5,"tags":[...],"comment":"..."}`       |
| POST   | `/api/friends/{id}/add`     | Promote a player to `FRIEND`                                    |
| POST   | `/api/checkout`             | Simulated Stripe test-mode upgrade → `{"status":"PRO_UNLOCKED"}`|

### Match Fit Score (0-100)

`PartyMatchService.calculateFitScore` is fully deterministic:

| Signal                                    | Points |
|-------------------------------------------|--------|
| Theme matches (or no theme filter)        | 45     |
| City matches (or no city filter)          | 25     |
| Party's `budgetMin` fits the max budget   | 20     |
| Party still has an open slot              | 10     |

Results below 65 are filtered out, so a search never returns a random party from another city.

### Live venue ratings

`Venue.avgRating` and `Venue.ratingCount` are recalculated on every rating submission. A re-rating
replaces the previous stars instead of counting twice (the store keeps a running `ratingSum`).

---

## Project layout

```text
crewparty/
├── pom.xml, Dockerfile, .gitignore, .env.example, README.md
└── src/main/
    ├── java/com/crewparty/
    │   ├── CrewPartyApplication.java
    │   ├── controller/ApiController.java
    │   ├── model/       Friend, Venue, Party, VisitRecord, requests, BootstrapResponse
    │   ├── repository/  InMemoryStoreRepository (demo data seed)
    │   └── service/     PartyMatchService, AiIcebreakerService
    └── resources/
        ├── application.properties
        └── static/      index.html, css/style.css, js/{api,map,app}.js
```

---

## External API disclosure

- **OpenAI Chat Completions (`gpt-4o-mini`)** — optional. Called only when a party is created and
  `OPENAI_API_KEY` is set, to generate 3 icebreakers. Without a key the built-in per-theme fallback
  list is used, so the app is fully functional offline and never fails in a demo.
- **Stripe** — the upgrade button is an intentionally simulated test-mode flow. No Stripe API call is
  made and no card data is sent or stored; the UI reads `4242 4242 4242 4242` as a pre-filled test card.
- **OpenStreetMap tiles** — loaded by Leaflet from `tile.openstreetmap.org` in the browser.

All demo data (players, venues, parties, visits) is fictional and lives in memory only; restarting the
app resets it.
