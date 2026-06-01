package com.kalynx.serverlessreviewtool.indexer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.serverlessreviewtool.plugin.dataobjects.ReviewListUpdate;
import com.kalynx.serverlessreviewtool.plugin.dataobjects.ReviewUpdateType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Connects to the ReviewTool Central Indexer on behalf of the application.
 *
 * <p>On start:
 * <ol>
 *   <li>Calls {@code GET /reviews} to fire an initial snapshot of all reviews.</li>
 *   <li>Opens a persistent SSE connection to {@code GET /events/stream} for incremental updates.</li>
 * </ol>
 *
 * <p>The SSE connection reconnects automatically after network interruptions.
 */
public class IndexerEventSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(IndexerEventSource.class);
    private static final long RECONNECT_DELAY_MS = 5_000L;

    private final SettingsManager settingsManager;
    private final HttpClient http;

    private static final Set<String> COMMENT_EVENT_TYPES = Set.of(
        "REVIEW_COMMENT_ADDED", "REVIEW_COMMENT_UPDATED", "comment.added", "comment.updated");

    private volatile Thread sseThread;
    private volatile List<String> lastFetchedBranches = List.of();
    private final Set<Consumer<List<String>>> branchListeners = new HashSet<>();
    private final Set<Consumer<CommentSseEvent>> commentListeners = new HashSet<>();

    public IndexerEventSource(SettingsManager settingsManager) {
        this(settingsManager, HttpClient.newHttpClient());
    }

    IndexerEventSource(SettingsManager settingsManager, HttpClient http) {
        this.settingsManager = settingsManager;
        this.http = http;
    }

    /**
     * Starts the indexer event source. Fires the current review snapshot immediately, then
     * maintains a live SSE subscription. Restarts automatically when the indexer URL changes.
     *
     * @param listener consumer that receives batched {@link ReviewListUpdate} arrays
     */
    public void start(Consumer<ReviewListUpdate[]> listener) {
        handleUrlChange(settingsManager.getIndexerUrl(), listener);
    }

    /**
     * Registers a listener that is called when a {@code REVIEW_COMMENT_ADDED} or
     * {@code REVIEW_COMMENT_UPDATED} SSE event arrives. The listener receives a
     * {@link CommentSseEvent} with the {@code reviewId}, {@code repositoryUrl}, and
     * {@code commentId} extracted from the event payload.
     *
     * @param listener consumer that receives comment SSE events
     */
    public void addCommentEventListener(Consumer<CommentSseEvent> listener) {
        commentListeners.add(listener);
    }

    /**
     * Registers a listener that receives a fresh branch list whenever the indexer URL is set or
     * changed. Fires immediately with the most recently fetched list (empty if not yet fetched).
     *
     * @param listener consumer that receives a list of branch names
     */
    public void addBranchListener(Consumer<List<String>> listener) {
        branchListeners.add(listener);
        listener.accept(lastFetchedBranches);
    }

    /** Stops the SSE connection. */
    public void stop() {
        Thread t = sseThread;
        sseThread = null;
        if (t != null) t.interrupt();
    }

    private void handleUrlChange(String url, Consumer<ReviewListUpdate[]> listener) {
        stop();
        if (url == null || url.isBlank()) return;

        fetchAndFireBranches(url);
        fetchAndFireInitialReviews(url, listener);

        Thread newThread = new Thread(() -> sseLoop(url, listener), "IndexerEventSource-SSE");
        newThread.setDaemon(true);
        sseThread = newThread;
        newThread.start();
    }

    private void sseLoop(String indexerUrl, Consumer<ReviewListUpdate[]> listener) {
        Thread self = Thread.currentThread();
        while (self == sseThread && !self.isInterrupted()) {
            try {
                connectSse(indexerUrl, listener, self);
                if (self == sseThread) Thread.sleep(RECONNECT_DELAY_MS);
            } catch (InterruptedException e) {
                self.interrupt();
                break;
            } catch (Exception e) {
                LOGGER.warn("Indexer SSE connection lost: {}", e.getMessage());
                if (self == sseThread) {
                    try {
                        Thread.sleep(RECONNECT_DELAY_MS);
                    } catch (InterruptedException ie) {
                        self.interrupt();
                        break;
                    }
                }
            }
        }
        LOGGER.debug("Indexer SSE thread exiting");
    }

    private void connectSse(String indexerUrl, Consumer<ReviewListUpdate[]> listener, Thread self)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(indexerUrl + "/events/stream?repository=*&since=0"))
            .header("Accept", "text/event-stream")
            .GET()
            .build();
        HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            LOGGER.warn("[SSE] Connection failed — indexer returned HTTP {}", response.statusCode());
            return;
        }
        LOGGER.info("[SSE] Connected to indexer SSE stream at {}", indexerUrl);
        parseSseStream(response.body(), listener, self);
    }

    private void parseSseStream(Stream<String> lines, Consumer<ReviewListUpdate[]> listener, Thread self) {
        SseFrame frame = new SseFrame();
        lines.takeWhile(_ -> self == sseThread && !self.isInterrupted())
            .forEach(line -> {
                if (line.isEmpty()) {
                    if (frame.hasData()) dispatchFrame(frame, listener);
                    frame.reset();
                } else if (line.startsWith("event: ")) {
                    frame.eventType = line.substring(7).trim();
                } else if (line.startsWith("data: ")) {
                    frame.data = line.substring(6).trim();
                }
            });
    }

    private void dispatchFrame(SseFrame frame, Consumer<ReviewListUpdate[]> listener) {
        LOGGER.info("[SSE] Frame received: eventType='{}' dataLength={}",
                frame.eventType, frame.data != null ? frame.data.length() : 0);

        if (COMMENT_EVENT_TYPES.contains(frame.eventType)) {
            dispatchCommentFrame(frame);
            return;
        }

        ReviewUpdateType type = mapEventType(frame.eventType);
        if (type == null) {
            LOGGER.info("[SSE] Ignoring unrecognised event type '{}'", frame.eventType);
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(frame.data).getAsJsonObject();
            String reviewId   = getString(obj, "reviewId");
            String repository = getString(obj, "repository");

            // repository_url and branch_name are nested inside the payload map
            JsonObject payload = obj.has("payload") && obj.get("payload").isJsonObject()
                    ? obj.getAsJsonObject("payload") : new JsonObject();
            String repoUrl    = getString(payload, "repository_url");
            String branchName = getString(payload, "branch_name");

            LOGGER.info("[SSE] Dispatching update: type='{}' reviewId='{}' repo='{}' repoUrl='{}' branch='{}'",
                    type, reviewId, repository, repoUrl, branchName);
            ReviewListUpdate update = new ReviewListUpdate(
                UUID.randomUUID().toString(), Instant.now(), type,
                reviewId, repository,
                repository != null ? List.of(repository) : List.of(),
                repoUrl, branchName);
            listener.accept(new ReviewListUpdate[]{update});
        } catch (Exception e) {
            LOGGER.warn("Failed to parse SSE event data: {}", e.getMessage());
        }
    }

    private void dispatchCommentFrame(SseFrame frame) {
        try {
            JsonObject obj = JsonParser.parseString(frame.data).getAsJsonObject();
            String reviewId = getString(obj, "review_id");
            String repositoryUrl = getString(obj, "repository_url");
            String commentId = getString(obj, "comment_id");

            LOGGER.info("[SSE] Dispatching comment event: type='{}' reviewId='{}' commentId='{}'",
                frame.eventType, reviewId, commentId);
            CommentSseEvent event = new CommentSseEvent(frame.eventType, reviewId, repositoryUrl, commentId);
            commentListeners.forEach(l -> l.accept(event));
        } catch (Exception e) {
            LOGGER.warn("Failed to parse SSE comment event data: {}", e.getMessage());
        }
    }

    void fetchAndFireBranches(String indexerUrl) {
        List<String> branches = fetchBranches(indexerUrl, 500);
        lastFetchedBranches = branches;
        branchListeners.forEach(l -> l.accept(branches));
    }

    List<String> fetchBranches(String indexerUrl, int limit) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(indexerUrl + "/branches?limit=" + limit))
                .header("Accept", "application/json")
                .GET()
                .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.warn("GET /branches returned {}", response.statusCode());
                return List.of();
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray arr = root.has("branches") ? root.getAsJsonArray("branches") : new JsonArray();
            List<String> result = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                if (!el.isJsonNull()) result.add(el.getAsString());
            }
            LOGGER.info("Loaded {} branches from indexer", result.size());
            return Collections.unmodifiableList(result);
        } catch (Exception e) {
            LOGGER.warn("Failed to fetch branches from indexer: {}", e.getMessage());
            return List.of();
        }
    }

    void fetchAndFireInitialReviews(String indexerUrl, Consumer<ReviewListUpdate[]> listener) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(indexerUrl + "/reviews"))
                .header("Accept", "application/json")
                .GET()
                .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.warn("GET /reviews returned {}", response.statusCode());
                return;
            }
            List<ReviewListUpdate> updates = parseReviews(response.body());
            LOGGER.info("Loaded {} reviews from indexer at startup", updates.size());
            if (!updates.isEmpty()) {
                listener.accept(updates.toArray(new ReviewListUpdate[0]));
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to fetch initial reviews from indexer: {}", e.getMessage());
        }
    }

    List<ReviewListUpdate> parseReviews(String body) {
        List<ReviewListUpdate> result = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray items = root.has("items") ? root.getAsJsonArray("items") : new JsonArray();
            for (JsonElement el : items) {
                JsonObject item = el.getAsJsonObject();
                String reviewId = getString(item, "review_id");
                if (reviewId == null) continue;
                String status = getString(item, "status");

                List<String> repoNames = new ArrayList<>();
                String primaryRepoUrl = null;
                if (item.has("repositories") && item.get("repositories").isJsonArray()) {
                    for (JsonElement repoEl : item.getAsJsonArray("repositories")) {
                        JsonObject repoObj = repoEl.getAsJsonObject();
                        String repo = getString(repoObj, "repository");
                        if (repo != null) repoNames.add(repo);
                        if (primaryRepoUrl == null) primaryRepoUrl = getString(repoObj, "repository_url");
                    }
                }

                ReviewUpdateType type = "COMPLETED".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status)
                    ? ReviewUpdateType.UPDATED
                    : ReviewUpdateType.CREATED;
                String primaryRepo = repoNames.isEmpty() ? null : repoNames.getFirst();
                result.add(new ReviewListUpdate(
                    UUID.randomUUID().toString(), Instant.now(), type,
                    reviewId, primaryRepo,
                    Collections.unmodifiableList(repoNames),
                    primaryRepoUrl, null));
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to parse /reviews response: {}", e.getMessage());
        }
        return result;
    }

    private ReviewUpdateType mapEventType(String eventType) {
        if (eventType == null) return null;
        return switch (eventType) {
            case "REVIEW_CREATED"                -> ReviewUpdateType.CREATED;
            case "REVIEW_UPDATED", "REVIEW_CLOSED",
                 "BRANCH_UPDATED"               -> ReviewUpdateType.UPDATED;
            case "BRANCH_DELETED"               -> ReviewUpdateType.DELETED;
            default                             -> null;
        };
    }

    private static String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static class SseFrame {
        String eventType;
        String data;

        boolean hasData() { return data != null; }

        void reset() {
            eventType = null;
            data = null;
        }
    }
}
