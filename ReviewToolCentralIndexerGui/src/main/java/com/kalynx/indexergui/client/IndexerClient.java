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
import java.util.Map;

/**
 * HTTP client for the Central Indexer API.
 * All methods are blocking; call from a background thread.
 */
public final class IndexerClient {

    // --- response types ----------------------------------------------------------

    public record SystemMetrics(
            double   cpu_percent,
            double   memory_mb,
            double   memory_max_mb,
            double   active_connections,
            long     api_calls_last_second,
            double[] per_core_cpu_percent,
            long     disk_free_mb,
            long     disk_total_mb) {}

    public record SseMetrics(
            long              connected_clients_total,
            double            writers_per_second,
            long              write_latency_p95_ms,
            Map<String, Long> events_by_type_last_second) {}

    public record WebhookMetrics(Map<String, Long> calls_by_type_last_second) {}

    public record RestMetrics(Map<String, Long> calls_by_type_last_second) {}

    public record ConnectionsMetrics(Map<String, Integer> by_client) {}

    public record MetricsSnapshot(
            SystemMetrics     system,
            SseMetrics        sse,
            WebhookMetrics    webhooks,
            RestMetrics       rest,
            ConnectionsMetrics connections) {}

    public record RepositoryEntry(String repository, String repository_url) {}

    public record ReviewItem(
            String review_id,
            String status,
            String last_updated,
            List<RepositoryEntry> repositories) {}

    public record BranchRecord(String owner, String repository, String branch_name) {}

    public record RepositoryItem(String owner, String repository, String url) {}

    public record CommentItem(String comment_id, String repository_url, String last_updated) {}

    // --- fields ------------------------------------------------------------------

    private volatile String  baseUrl;
    private final HttpClient http;
    private final Gson       gson;

    /**
     * Constructs an {@code IndexerClient} targeting the given host and port.
     *
     * @param host indexer host name or IP address
     * @param port indexer HTTP port
     */
    public IndexerClient(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port;
        this.http    = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.gson    = new GsonBuilder().serializeNulls().create();
    }

    /**
     * Updates the target host and port without replacing the underlying HTTP client.
     * Safe to call while requests may be in-flight; subsequent requests will use
     * the new address.
     *
     * @param host new indexer host name or IP address
     * @param port new indexer HTTP port
     */
    public void setConnection(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port;
    }

    // --- metrics -----------------------------------------------------------------

    public MetricsSnapshot getMetrics() throws IOException, InterruptedException {
        Type mapStringLong    = new TypeToken<Map<String, Long>>()    {}.getType();
        Type mapStringInt     = new TypeToken<Map<String, Integer>>() {}.getType();

        JsonObject root = JsonParser.parseString(get("/metrics")).getAsJsonObject();

        JsonObject sys = root.getAsJsonObject("system");
        if (sys == null) throw new IOException("No 'system' section in /metrics response");
        double[] cores = gson.fromJson(sys.get("per_core_cpu_percent"), double[].class);
        SystemMetrics systemMetrics = new SystemMetrics(
                sys.get("cpu_percent").getAsDouble(),
                sys.get("memory_mb").getAsDouble(),
                sys.get("memory_max_mb").getAsDouble(),
                sys.get("active_connections").getAsDouble(),
                sys.get("api_calls_last_second").getAsLong(),
                cores != null ? cores : new double[0],
                sys.has("disk_free_mb")  ? sys.get("disk_free_mb").getAsLong()  : 0L,
                sys.has("disk_total_mb") ? sys.get("disk_total_mb").getAsLong() : 0L);

        JsonObject sseObj = root.getAsJsonObject("sse");
        SseMetrics sseMetrics = null;
        if (sseObj != null) {
            Map<String, Long> evtMap = gson.fromJson(sseObj.get("events_by_type_last_second"), mapStringLong);
            sseMetrics = new SseMetrics(
                    sseObj.get("connected_clients_total").getAsLong(),
                    sseObj.get("writers_per_second").getAsDouble(),
                    sseObj.get("write_latency_p95_ms").getAsLong(),
                    evtMap != null ? evtMap : Map.of());
        }

        JsonObject whObj = root.getAsJsonObject("webhooks");
        WebhookMetrics webhookMetrics = null;
        if (whObj != null) {
            Map<String, Long> whMap = gson.fromJson(whObj.get("calls_by_type_last_second"), mapStringLong);
            webhookMetrics = new WebhookMetrics(whMap != null ? whMap : Map.of());
        }

        JsonObject restObj = root.getAsJsonObject("rest");
        RestMetrics restMetrics = null;
        if (restObj != null) {
            Map<String, Long> restMap = gson.fromJson(restObj.get("calls_by_type_last_second"), mapStringLong);
            restMetrics = new RestMetrics(restMap != null ? restMap : Map.of());
        }

        JsonObject connObj = root.getAsJsonObject("connections");
        ConnectionsMetrics connMetrics = null;
        if (connObj != null) {
            Map<String, Integer> byClient = gson.fromJson(connObj.get("by_client"), mapStringInt);
            connMetrics = new ConnectionsMetrics(byClient != null ? byClient : Map.of());
        }

        return new MetricsSnapshot(systemMetrics, sseMetrics, webhookMetrics, restMetrics, connMetrics);
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

    // --- comments ----------------------------------------------------------------

    public List<CommentItem> getComments(String reviewId) throws IOException, InterruptedException {
        String path = "/reviews/" + URLEncoder.encode(reviewId, StandardCharsets.UTF_8) + "/comments";
        Type listType = new TypeToken<List<CommentItem>>() {}.getType();
        List<CommentItem> items = gson.fromJson(get(path), listType);
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
