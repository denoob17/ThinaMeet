package com.crewparty.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates 3 theme-specific conversation starters when a party is created.
 * Uses OpenAI Chat Completions when {@code OPENAI_API_KEY} is set and falls back to a
 * deterministic local list otherwise, so the demo never depends on the network.
 */
@Service
public class AiIcebreakerService {

    private static final Logger log = LoggerFactory.getLogger(AiIcebreakerService.class);

    private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL = "gpt-4o-mini";

    /** Kept short on purpose: creating a party must never make the UI hang on a slow upstream. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(6);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private static final Map<String, List<String>> FALLBACKS = Map.of(
            "Board Games", List.of(
                    "What is the most chaotic house rule your group ever invented?",
                    "Which game do you always bring to a table, no matter the crowd?",
                    "Casual strategy or aggressive trade deals — which kind of player are you?"),
            "Co-op Video Games", List.of(
                    "What is your role when the squad is one player short?",
                    "Best couch co-op game of the last decade — go.",
                    "What is the funniest wipe your team has ever had?"),
            "Coworking & Chill", List.of(
                    "What are you working on this week that you are actually excited about?",
                    "Deep work playlist or total silence — which one gets you through the afternoon?",
                    "What is one small thing that makes a coworking spot great for you?"),
            "Trivia & Social", List.of(
                    "Which category would you bet everything on: music, sports or 90s TV?",
                    "What is the most useless fact you still remember from school?",
                    "Team rule check: do you shout the answer or wait for the captain?"),
            "Outdoor Casual", List.of(
                    "Sunrise walk or golden-hour hangout — which one is your speed?",
                    "What is the best low-key outdoor spot in this city?",
                    "Coffee, boba or a cold drink for a walk-and-talk?"),
            "default", List.of(
                    "What made you want to join a new crew this week?",
                    "What is the one thing that always makes a hangout fun for you?",
                    "Pick a snack for the table — what are we ordering?"));

    private final String apiKey;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    public AiIcebreakerService(@Value("${openai.api.key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    public List<String> generateIcebreakers(String theme, String title, String city) {
        if (apiKey == null || apiKey.isBlank()) {
            return fallbackFor(theme);
        }
        try {
            List<String> generated = callOpenAi(theme, title, city);
            return generated.isEmpty() ? fallbackFor(theme) : generated;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("Icebreaker request interrupted, using the built-in fallback list.");
            return fallbackFor(theme);
        } catch (Exception failure) {
            log.warn("Icebreaker request failed ({}), using the built-in fallback list.", failure.getMessage());
            return fallbackFor(theme);
        }
    }

    private List<String> callOpenAi(String theme, String title, String city) throws Exception {
        String prompt = "You are a friendly social host in " + city + ". "
                + "A group is hosting a \"" + theme + "\" hangout titled \"" + title + "\". "
                + "Write exactly 3 short, warm, specific icebreaker conversation starters "
                + "that total strangers could use at the table. "
                + "Return ONLY a JSON array of 3 strings.";

        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");
        message.put("content", prompt);

        ArrayNode messages = objectMapper.createArrayNode().add(message);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", MODEL);
        body.put("temperature", 0.8);
        body.set("messages", messages);

        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("OpenAI responded with HTTP " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        return parseIcebreakers(content.asText(""));
    }

    private List<String> parseIcebreakers(String content) throws Exception {
        String cleaned = content.replace("```json", "").replace("```", "").trim();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        JsonNode array = objectMapper.readTree(cleaned);
        List<String> icebreakers = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(node -> icebreakers.add(node.asText().trim()));
        }
        return icebreakers.stream().filter(item -> !item.isBlank()).limit(3).toList();
    }

    private List<String> fallbackFor(String theme) {
        List<String> themed = FALLBACKS.get(theme);
        return new ArrayList<>(themed == null ? FALLBACKS.get("default") : themed);
    }
}
