package com.example.chatbot_backend;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class WebScrapingService {

    private static final Logger log = LoggerFactory.getLogger(WebScrapingService.class);
    // Using DuckDuckGo's HTML version - less likely to have heavy JS or blocks
    private static final String DUCKDUCKGO_URL = "https://html.duckduckgo.com/html/?q=";
    // Standard User-Agent to mimic a browser
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/96.0.4664.110 Safari/537.36";
    private static final int TIMEOUT_MS = 10000; // 10 seconds timeout
    private static final int MAX_RESULTS = 3; // Limit number of results to extract

    /**
     * Performs a web search on DuckDuckGo HTML version and scrapes results.
     *
     * @param query The search query.
     * @return A List of simple String snippets from the search results, or an empty list if error/no results.
     */
    public List<String> searchAndScrape(String query) {
        List<String> results = new ArrayList<>();
        try {
            // Encode the query for the URL
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String searchUrl = DUCKDUCKGO_URL + encodedQuery;
            log.info("Attempting to scrape search results from: {}", searchUrl);

            // Fetch the HTML document using Jsoup
            Document doc = Jsoup.connect(searchUrl)
                    .userAgent(USER_AGENT) // Set user agent to avoid basic blocks
                    .timeout(TIMEOUT_MS)   // Set connection timeout
                    .get();                // Execute the GET request

            // --- Selector Logic (THIS IS THE FRAGILE PART) ---
            // Inspect DuckDuckGo HTML source to find the right CSS selectors.
            // These selectors might change if DDG updates its layout.
            // Common selectors for DDG HTML results (verify by inspecting source):
            // Results are often within divs with class="result" or "web-result"
            // The snippet/description might be in a div with class="result__snippet" or similar
            Elements resultElements = doc.select("div.result, div.web-result"); // Try common class names

            log.debug("Found {} potential result elements.", resultElements.size());

            int count = 0;
            for (Element result : resultElements) {
                if (count >= MAX_RESULTS) {
                    break; // Stop after reaching the limit
                }

                // Find the snippet text within each result element
                Element snippetElement = result.selectFirst(".result__snippet, .result__body"); // Find snippet element
                if (snippetElement != null) {
                    String snippetText = snippetElement.text().trim(); // Get the text content
                    if (!snippetText.isEmpty()) {
                        results.add("- " + snippetText); // Add formatted snippet to list
                        log.debug("Extracted snippet: {}", snippetText);
                        count++;
                    }
                } else {
                     // Fallback: try getting the title if snippet is missing
                     Element titleElement = result.selectFirst(".result__title a, .result__a");
                     if (titleElement != null) {
                         String titleText = titleElement.text().trim();
                         if(!titleText.isEmpty()) {
                             results.add("- " + titleText + " (Title only)");
                             log.debug("Extracted title as fallback: {}", titleText);
                             count++;
                         }
                     }
                }
            }

            if (results.isEmpty()) {
                log.warn("No snippets extracted from search results page for query: {}", query);
            }

        } catch (IOException e) {
            log.error("IOException during Jsoup connection or parsing for query '{}': {}", query, e.getMessage());
            // Don't propagate exception, return empty list to indicate failure
        } catch (Exception e) {
            log.error("Unexpected error during web scraping for query '{}': {}", query, e.getMessage(), e);
            // Catch other potential errors (e.g., selector syntax)
        }

        return results;
    }
}