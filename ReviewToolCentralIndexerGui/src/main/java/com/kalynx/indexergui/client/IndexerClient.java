package com.kalynx.indexergui.client;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * HTTP client for the Central Indexer API.
 * All methods are blocking; call from a background thread.
 */
public final class IndexerClient {

    // --- response types ----------------------------------------------------------

    public record SystemMetrics(
            double cpu_percent,
            double memory_mb,
            double memory_max_mb,
            double active_connections,
            long   api_calls_last_second,
            double[] per_core_cpu_percent) {}

    public record MetricsSnapshot(SystemMetrics system) {}

    public record RepositoryEntry(String repository, String repository_url) {}

    public record ReviewItem(
            String review_id,
            String status,
            String last_updated,
            List<RepositoryEntry> repositories) {}

    public record BranchRecord(String owner, String repository, String branch_name) {}

    public record RepositoryItem(String owner, String repository, String url) {}

    // --- fields ------------------------------------------------------------------

    private final String     baseUrl;
    private final HttpClient http;
    private final Gson       gson;

    public IndexerClient(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port;
        this.http    = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.gson    = new GsonBuilder().serializeNulls().create();
    }

    // --- metrics -----------------------------------------------------------------

    public MetricsSnapshot getMetrics() throws IOException, InterruptedException {
        JsonObject root = JsonParser.parseString(get("/metrics")).getAsJsonObject();
        JsonObject sys  = root.getAsJsonObject("system");
        if (sys == null) throw new IOException("No 'system' section in /metrics response");
        double[] cores = gson.fromJson(sys.get("per_core_cpu_percent"), double[].class);
        return new MetricsSnapshot(new SystemMetrics(
                sys.get("cpu_percent").getAsDouble(),
                sys.get("memory_mb").getAsDouble(),
                sys.get("memory_max_mb").getAsDouble(),
                sys.get("active_connections").getAsDouble(),
                sys.get("api_calls_last_second").getAsLong(),
                cores != null ? cores : new double[0]));
    }

    // --- reviews -----------------------------------------------------------------

    public List<ReviewItem> getReviews(Instant since, List<String> statuses)
            throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("/reviews");
        char sep = '?';
        if (since != null) {
            path.append(sep).append("since=")
                .append(URLEncoder.encode(since.toString(), StandardCharsets.UTF_8));
            sep = '&';
        }
        if (statuses != null && !statuses.isEmpty()) {
            path.append(sep).append("status=")
                .append(URLEncoder.encode(String.join(",", statuses), StandardCharsets.UTF_8));
        }
        JsonObject root = JsonParser.parseString(get(path.toString())).getAsJsonObject();
        Type listType = new TypeToken<List<ReviewItem>>() {}.getType();
        List<ReviewItem> items = gson.fromJson(root.get("items"), listType);
        return items != null ? items : List.of();
    }

    // --- branches ----------------------------------------------------------------

    public List<BranchRecord> getBranches(String prefix, String owner, String repo)
            throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("/branches?limit=500&detailed=true");
        if (prefix != null && !prefix.isBlank()) {
            path.append("&q=").append(URLEncoder.encode(prefix.strip(), StandardCharsets.UTF_8));
        }
        if (owner != null && !owner.isBlank() && repo != null && !repo.isBlank()) {
            path.append("&repository=").append(
                    URLEncoder.encode(owner.strip() + "/" + repo.strip(), StandardCharsets.UTF_8));
        }
        JsonObject root = JsonParser.parseString(get(path.toString())).getAsJsonObject();
        Type listType = new TypeToken<List<BranchRecord>>() {}.getType();
        JsonElement detail = root.get("branch_records");
        if (detail == null || detail.isJsonNull()) return List.of();
        List<BranchRecord> records = gson.fromJson(detail, listType);
        return records != null ? records : List.of();
    }

    // --- repositories ------------------------------------------------------------

    public List<RepositoryItem> getRepositories() throws IOException, InterruptedException {
        JsonObject root = JsonParser.parseString(get("/repositories")).getAsJsonObject();
        Type listType = new TypeToken<List<RepositoryItem>>() {}.getType();
        List<RepositoryItem> items = gson.fromJson(root.get("items"), listType);
        return items != null ? items : List.of();
    }

    // --- HTTP helper -------------------------------------------------------------

    private String get(String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException("HTTP " + resp.statusCode() + " from " + path);
        }
        return resp.body();
    }
}
