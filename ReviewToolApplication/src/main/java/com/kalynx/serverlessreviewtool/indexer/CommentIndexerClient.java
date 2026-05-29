package com.kalynx.serverlessreviewtool.indexer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CommentIndexerClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommentIndexerClient.class);

    private final SettingsManager settingsManager;
    private final HttpClient http;

    public CommentIndexerClient(SettingsManager settingsManager) {
        this(settingsManager, HttpClient.newHttpClient());
    }

    CommentIndexerClient(SettingsManager settingsManager, HttpClient http) {
        this.settingsManager = settingsManager;
        this.http = http;
    }

    public List<CommentRoutingEntry> getCommentRouting(String reviewId) {
        String indexerUrl = settingsManager.getIndexerUrl();
        if (indexerUrl == null || indexerUrl.isBlank()) {
            return List.of();
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(indexerUrl + "/reviews/" + reviewId + "/comments"))
                .header("Accept", "application/json")
                .GET();

            String token = settingsManager.getIndexerBearerToken();
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }

            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status == 404) {
                return List.of();
            }
            if (status == 401) {
                LOGGER.warn("GET /reviews/{}/comments returned 401 — check indexer bearer token", reviewId);
                throw new IndexerAuthException("Indexer returned 401 for review " + reviewId);
            }
            if (status != 200) {
                LOGGER.warn("GET /reviews/{}/comments returned {}", reviewId, status);
                return List.of();
            }
            return parseEntries(response.body());
        } catch (IndexerAuthException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.warn("Failed to fetch comment routing for review {}: {}", reviewId, e.getMessage());
            return List.of();
        }
    }

    private List<CommentRoutingEntry> parseEntries(String body) {
        try {
            JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
            List<CommentRoutingEntry> result = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                String repoUrl = getString(obj, "repository_url");
                String commentId = getString(obj, "comment_id");
                String lastUpdated = getString(obj, "last_updated");
                if (repoUrl != null && commentId != null) {
                    result.add(new CommentRoutingEntry(repoUrl, commentId, lastUpdated));
                }
            }
            return Collections.unmodifiableList(result);
        } catch (Exception e) {
            LOGGER.warn("Failed to parse comment routing response: {}", e.getMessage());
            return List.of();
        }
    }

    private static String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }
}
