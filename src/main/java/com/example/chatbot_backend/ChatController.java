package com.example.chatbot_backend;

// Remove Gson imports if they are no longer needed elsewhere
// import com.google.gson.JsonArray;
// import com.google.gson.JsonElement;
// import com.google.gson.JsonObject;
import org.json.JSONObject; // Keep this for Ollama payload
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value; // For Ollama config
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    // ~~ Remove injection of SerpApiService ~~
    // @Autowired
    // private SerpApiService serpApiService;

    // ++ Inject the WebScrapingService ++
    @Autowired
    private WebScrapingService webScrapingService;

    @Value("${ollama.api.url:http://localhost:11434/api/generate}")
    private String ollamaApiUrl;

    @Value("${ollama.model.name:mistral}")
    private String ollamaModelName;

    // Keep the heuristic keywords
    private static final List<String> SEARCH_KEYWORDS = Arrays.asList(
            "breaking", "latest", "current", "today", "live", "update", "emergency", "alert", "trending", "headline", "recent", "news", "developing", "real-time", "urgent", "immediate", "now", "global", "world", "election", "weather", "stock", "finance", "sports", "results", "polls", "technology", "market", "viral", "scoop","yesterday", "weather", "stock", "news", "price of", "who is", "what is the capital of", "define"
            // Add more specific triggers if needed
    );

    @PostMapping("/api/chat")
    public Map<String, String> handleChatMessage(@RequestBody Map<String, String> payload) {
        String userMessage = payload.get("message");
        if (userMessage == null || userMessage.trim().isEmpty()) {
            log.warn("Received empty message payload.");
            return Map.of("error", "Message not provided in the request.");
        }

        log.info("Received user message: {}", userMessage);

        String finalPrompt = userMessage;

        // --- Heuristic Check (remains the same) ---
        if (shouldSearchWeb(userMessage)) {
            log.info("Heuristic triggered: Searching web for query '{}'", userMessage);
            // ++ Call the new WebScrapingService ++
            List<String> searchSnippets = webScrapingService.searchAndScrape(userMessage);

            // ++ Check if the list of snippets is not empty ++
            if (searchSnippets != null && !searchSnippets.isEmpty()) {
                // --- Format Search Results (now simpler) ---
                String formattedResults = formatScrapedResults(searchSnippets); // Use updated formatting method

                // Construct the prompt (same logic as before)
                finalPrompt = String.format(
                        "Based on the following web search results:\n\"\"\"\n%s\n\"\"\"\n\nAnswer the user's original question: %s",
                        formattedResults,
                        userMessage
                );
                log.info("Generated prompt with scraped web results.");
                log.debug("Prompt snippet: {}", finalPrompt.substring(0, Math.min(finalPrompt.length(), 150)) + "...");
            } else {
                 log.warn("Web scraping failed or returned no snippets for query: {}", userMessage);
                 // Keep finalPrompt as the original userMessage (fallback)
            }
        } else {
            log.info("Heuristic not triggered. Sending original message to LLM.");
        }

        // --- Call Ollama with the final prompt (remains the same) ---
        String botResponse = getOllamaResponse(finalPrompt);
        return Map.of("response", botResponse);
    }

    /**
     * Checks if the user message triggers the web search heuristic. (No changes needed)
     */
    private boolean shouldSearchWeb(String message) {
        if (message == null || message.trim().isEmpty()) {
            return false;
        }
        String lowerCaseMessage = message.toLowerCase();
        return SEARCH_KEYWORDS.stream().anyMatch(lowerCaseMessage::contains);
    }

    /**
     * ++ Renamed and Simplified: Formats the list of scraped snippets into a single string. ++
     *
     * @param snippets A list of strings, where each string is a scraped snippet.
     * @return A single string with snippets joined by newlines.
     */
    private String formatScrapedResults(List<String> snippets) {
        // Simply join the list elements with a newline character
        return String.join("\n", snippets);
    }

    // ~~ Remove the old formatSearchResults method that used JsonObject ~~
    /*
    private String formatSearchResults(JsonObject searchResults) {
        // ... old logic using Gson JsonObject ...
    }
    */


    /**
     * Calls the Ollama API with the given prompt. (No changes needed here)
     */
    private String getOllamaResponse(String prompt) {
        StringBuilder finalResponse = new StringBuilder();
        try {
            URL url = new URL(ollamaApiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);

            JSONObject requestPayload = new JSONObject();
            requestPayload.put("model", ollamaModelName);
            requestPayload.put("prompt", prompt);
            requestPayload.put("stream", false);

            log.debug("Sending prompt to Ollama. Model: {}, URL: {}", ollamaModelName, ollamaApiUrl);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestPayload.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            log.debug("Ollama API response code: {}", responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                 try (BufferedReader reader = new BufferedReader(
                         new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                     String responseBody = reader.lines().collect(Collectors.joining("\n"));
                     if (!responseBody.trim().isEmpty()) {
                         JSONObject jsonResponse = new JSONObject(responseBody);
                         String responseText = jsonResponse.optString("response", "");
                         finalResponse.append(responseText);
                     }
                 }
            } else {
                 try (BufferedReader errorReader = new BufferedReader(
                         new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                     String errorBody = errorReader.lines().collect(Collectors.joining("\n"));
                     log.error("Error response from Ollama ({}): {}", responseCode, errorBody);
                     return "Error from Ollama API: " + responseCode + " - " + errorBody;
                 } catch (Exception ioEx) {
                     log.error("Could not read error stream from Ollama: {}", ioEx.getMessage());
                     return "Error from Ollama API: " + responseCode;
                 }
            }

            conn.disconnect();

            String aggregated = finalResponse.toString().trim();
            log.info("Received Ollama response length: {}", aggregated.length());
            return aggregated.isEmpty() ? "No valid response received from Ollama." : aggregated;

        } catch (Exception e) {
            log.error("Error calling Ollama API: {}", e.getMessage(), e);
            return "Error calling Ollama API: " + e.getMessage();
        }
    }
}