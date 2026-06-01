package com.kalynx.serverlessreviewtool.git;

import com.google.gson.reflect.TypeToken;
import com.kalynx.serverlessreviewtool.models.review.ReviewerData;
import com.kalynx.serverlessreviewtool.models.review.RepositoryActiveData;
import com.kalynx.serverlessreviewtool.models.review.StreamEntry;
import com.kalynx.serverlessreviewtool.utils.NdjsonReader;
import com.kalynx.serverlessreviewtool.utils.NdjsonWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Manages review data stored as NDJSON files on the {@code refs/heads/kalynx-reviews}
 * orphan branch via {@link OrphanBranchStore}.
 *
 * <p>Each stream path holds exactly <em>one</em> NDJSON line (current state).
 * Git history on the orphan branch serves as the audit log; there is no client-side
 * stream replay.
 *
 * @see OrphanBranchStore
 */
public class OrphanBranchReviewManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrphanBranchReviewManager.class);

    private final OrphanBranchStore store;
    private final String repositoryName;

    // Record types for comment data.
    public record CommentMetadata(String file, int line, int lineEnd, String commit) {}
    public record CommentTextData(String text, String replyTo, String type) {}
    public record CommentStatusData(Boolean needsResolution, Boolean resolved) {}

    /** Container for all review metadata retrieved in a single batch read. */
    public record ReviewMetadata(
        List<StreamEntry<String>> titles,
        List<StreamEntry<String>> descriptions,
        List<StreamEntry<String>> authors,
        List<StreamEntry<String>> primaryRepository,
        List<StreamEntry<String>> branches,
        List<StreamEntry<String>> baseBranches,
        List<StreamEntry<String>> statuses,
        List<StreamEntry<ReviewerData>> reviewers,
        List<StreamEntry<RepositoryActiveData>> repositoryActiveEntries
    ) {}

    public OrphanBranchReviewManager(OrphanBranchStore store, String repositoryName) {
        this.store = store;
        this.repositoryName = repositoryName;
    }

    // -------------------------------------------------------------------------
    // Single-field writes — each produces exactly one NDJSON line stored as a blob.
    // -------------------------------------------------------------------------

    public CompletableFuture<Void> writeReviewTitle(String reviewId, String editor, String title) {
        return writeField(reviewId, "metadata/title", editor, title);
    }

    public CompletableFuture<Void> writeReviewDescription(String reviewId, String editor, String description) {
        return writeField(reviewId, "metadata/description", editor, description);
    }

    public CompletableFuture<Void> writeReviewAuthor(String reviewId, String editor, String author) {
        return writeField(reviewId, "metadata/author", editor, author);
    }

    public CompletableFuture<Void> writeReviewStatus(String reviewId, String editor, String status) {
        return writeField(reviewId, "metadata/status", editor, status);
    }

    public CompletableFuture<Void> writeReviewCommits(String reviewId, String editor, List<String> commits) {
        return writeField(reviewId, "metadata/commits", editor, commits);
    }

    public CompletableFuture<Void> writeReviewer(String reviewId, String editor, ReviewerData reviewerData) {
        StreamEntry<ReviewerData> entry = StreamEntry.create(editor, reviewerData);
        byte[] newLine = NdjsonWriter.toBytes(entry);
        return store.readFile(reviewId, "reviewers")
            .thenCompose(existing -> {
                byte[] combined = appendNdjsonLine(existing.orElse(new byte[0]), newLine);
                return store.writeFile(reviewId, "reviewers", combined);
            });
    }

    public CompletableFuture<Void> writeRepositoryActive(String reviewId, String repositoryName,
                                                          String editor, boolean active) {
        StreamEntry<RepositoryActiveData> entry = StreamEntry.create(editor, new RepositoryActiveData(repositoryName, active));
        byte[] newLine = NdjsonWriter.toBytes(entry);
        return store.readFile(reviewId, "metadata/repositoryActive")
            .thenCompose(existing -> {
                byte[] combined = appendNdjsonLine(existing.orElse(new byte[0]), newLine);
                return store.writeFile(reviewId, "metadata/repositoryActive", combined);
            });
    }

    public CompletableFuture<Void> writeCommentMetadata(String reviewId, String commentId, String editor,
                                                         String file, int line, int lineEnd, String commit) {
        return writeField(reviewId, "comments/" + commentId + "/metadata", editor,
                new CommentMetadata(file, line, lineEnd, commit));
    }

    public CompletableFuture<Void> writeCommentText(String reviewId, String commentId, String editor,
                                                     String text, String replyTo, String type) {
        return writeField(reviewId, "comments/" + commentId + "/text", editor,
                new CommentTextData(text, replyTo, type));
    }

    public CompletableFuture<Void> writeCommentStatus(String reviewId, String commentId, String editor,
                                                       Boolean needsResolution, Boolean resolved) {
        return writeField(reviewId, "comments/" + commentId + "/status", editor,
                new CommentStatusData(needsResolution, resolved));
    }

    // -------------------------------------------------------------------------
    // Batch create — all paths written in a single commit.
    // -------------------------------------------------------------------------

    /**
     * Creates a new review by writing all fields atomically in one commit.
     */
    public CompletableFuture<Void> createReview(String reviewId,
                                                 String editor,
                                                 String title,
                                                 String author,
                                                 String description,
                                                 String status,
                                                 List<String> commits,
                                                 List<String> reviewers,
                                                 String branch,
                                                 String baseBranch) {
        List<String> safeCommits = commits != null ? commits : List.of();
        Map<String, byte[]> files = new LinkedHashMap<>();
        put(files, reviewId, "metadata/title", editor, title);
        put(files, reviewId, "metadata/author", editor, author);
        put(files, reviewId, "metadata/description", editor, description);
        put(files, reviewId, "metadata/status", editor, status);
        put(files, reviewId, "metadata/commits", editor, safeCommits);
        put(files, reviewId, "metadata/primaryRepository", editor, "true");
        put(files, reviewId, "metadata/branch", editor, branch);
        put(files, reviewId, "metadata/baseBranch", editor, baseBranch);
        for (String reviewer : reviewers != null ? reviewers : List.<String>of()) {
            putMerged(files, reviewId, "reviewers", editor, reviewer,
                    new ReviewerData(ReviewerData.Status.PENDING.getValue(), ""));
        }
        String repoStreamPath = "metadata/repositoryActive";
        putMerged(files, reviewId, repoStreamPath, editor, repositoryName,
                new RepositoryActiveData(repositoryName, true));

        return store.writeFiles(reviewId, stripReviewPrefix(reviewId, files));
    }

    /**
     * Creates a secondary review reference in this repository (non-primary participant).
     */
    public CompletableFuture<Void> createSecondaryReviewReference(String reviewId, String editor,
                                                                    List<String> commits,
                                                                    String branch, String baseBranch) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        put(files, reviewId, "metadata/primaryRepository", editor, "false");
        put(files, reviewId, "metadata/branch", editor, branch);
        put(files, reviewId, "metadata/baseBranch", editor, baseBranch);
        if (commits != null && !commits.isEmpty()) {
            put(files, reviewId, "metadata/commits", editor, commits);
        }
        return store.writeFiles(reviewId, stripReviewPrefix(reviewId, files));
    }

    // -------------------------------------------------------------------------
    // Batch comment write — all streams for one comment in a single commit.
    // -------------------------------------------------------------------------

    /**
     * Writes metadata, text, and optionally status for a single comment in one commit+push.
     * {@code statusData} may be {@code null} when neither needsResolution nor resolved is set.
     */
    public CompletableFuture<Void> writeComment(String reviewId, String commentId, String editor,
                                                 CommentMetadata metadata,
                                                 CommentTextData text,
                                                 CommentStatusData status) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        put(files, reviewId, "comments/" + commentId + "/metadata", editor, metadata);
        put(files, reviewId, "comments/" + commentId + "/text", editor, text);
        if (status != null) {
            put(files, reviewId, "comments/" + commentId + "/status", editor, status);
        }
        return store.writeFiles(reviewId, stripReviewPrefix(reviewId, files));
    }

    // -------------------------------------------------------------------------
    // Batch comment-status write — single commit for N comments.
    // -------------------------------------------------------------------------

    /**
     * Writes the status stream for every comment in {@code comments} in a single commit+push.
     * Use this instead of calling {@link #writeCommentStatus} N times when only resolution
     * state has changed for multiple comments at once (e.g. "Mark all resolved").
     */
    public CompletableFuture<Void> writeAllCommentStatuses(String reviewId, String editor,
                                                            List<CommentStatusEntry> comments) {
        if (comments == null || comments.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (CommentStatusEntry e : comments) {
            put(files, reviewId, "comments/" + e.commentId() + "/status", editor,
                    new CommentStatusData(e.needsResolution(), e.resolved()));
        }
        return store.writeFiles(reviewId, stripReviewPrefix(reviewId, files));
    }

    /** Carries the per-comment data needed by {@link #writeAllCommentStatuses}. */
    public record CommentStatusEntry(String commentId, Boolean needsResolution, Boolean resolved) {}

    // -------------------------------------------------------------------------
    // Batch metadata save
    // -------------------------------------------------------------------------

    public CompletableFuture<Void> saveAllMetadataBatch(String reviewId, String editor,
                                                         String title, String description,
                                                         String author, String status,
                                                         List<Map.Entry<String, ReviewerData>> reviewerEntries) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        put(files, reviewId, "metadata/title", editor, title);
        put(files, reviewId, "metadata/description", editor, description);
        put(files, reviewId, "metadata/author", editor, author);
        put(files, reviewId, "metadata/status", editor, status);
        for (Map.Entry<String, ReviewerData> entry : reviewerEntries) {
            putMerged(files, reviewId, "reviewers", entry.getKey(), entry.getKey(), entry.getValue());
        }
        return store.writeFiles(reviewId, stripReviewPrefix(reviewId, files));
    }

    // -------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------

    public CompletableFuture<List<StreamEntry<String>>> readTitles(String reviewId) {
        return readField(reviewId, "metadata/title", String.class);
    }

    public CompletableFuture<List<StreamEntry<String>>> readDescriptions(String reviewId) {
        return readField(reviewId, "metadata/description", String.class);
    }

    public CompletableFuture<List<StreamEntry<String>>> readAuthors(String reviewId) {
        return readField(reviewId, "metadata/author", String.class);
    }

    public CompletableFuture<List<StreamEntry<String>>> readStatuses(String reviewId) {
        return readField(reviewId, "metadata/status", String.class);
    }

    public CompletableFuture<List<StreamEntry<List<String>>>> readCommits(String reviewId) {
        Type type = TypeToken.getParameterized(StreamEntry.class,
                TypeToken.getParameterized(List.class, String.class).getType()).getType();
        return readFieldWithType(reviewId, "metadata/commits", type);
    }

    public CompletableFuture<List<StreamEntry<ReviewerData>>> readReviewers(String reviewId) {
        return readField(reviewId, "reviewers", ReviewerData.class);
    }

    public CompletableFuture<List<StreamEntry<CommentMetadata>>> readCommentMetadata(
            String reviewId, String commentId) {
        return readField(reviewId, "comments/" + commentId + "/metadata", CommentMetadata.class);
    }

    public CompletableFuture<List<StreamEntry<CommentTextData>>> readCommentText(
            String reviewId, String commentId) {
        return readField(reviewId, "comments/" + commentId + "/text", CommentTextData.class);
    }

    public CompletableFuture<List<StreamEntry<CommentStatusData>>> readCommentStatus(
            String reviewId, String commentId) {
        return readField(reviewId, "comments/" + commentId + "/status", CommentStatusData.class);
    }

    /**
     * Lists all review IDs stored on the orphan branch for this repository.
     *
     * @return future containing the list of review IDs
     */
    public CompletableFuture<List<String>> listReviewIds() {
        return store.listReviewIds();
    }

    /**
     * Lists comment IDs by walking the {@code reviews/<reviewId>/comments/} subtree on the orphan branch.
     */
    public CompletableFuture<List<String>> listCommentIds(String reviewId) {
        return store.listCommentIds(reviewId);
    }

    /**
     * Holds all three parsed sub-streams for one comment thread.
     * Empty lists indicate the stream doesn't exist on the branch yet.
     */
    public record AllCommentData(
        List<StreamEntry<CommentMetadata>> metadata,
        List<StreamEntry<CommentTextData>> text,
        List<StreamEntry<CommentStatusData>> status
    ) {}

    /**
     * Reads all three sub-streams for every comment ID in {@code commentIds} using a single
     * {@code git cat-file --batch} call. Replaces N×3 individual subprocess calls with one.
     *
     * @return future containing a map from comment ID to its parsed stream data;
     *         comment IDs not found on the branch will have empty stream lists
     */
    public CompletableFuture<Map<String, AllCommentData>> readAllComments(String reviewId,
                                                                           List<String> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return CompletableFuture.completedFuture(new LinkedHashMap<>());
        }
        List<String> streamPaths = new ArrayList<>(commentIds.size() * 3);
        for (String id : commentIds) {
            streamPaths.add("comments/" + id + "/metadata");
            streamPaths.add("comments/" + id + "/text");
            streamPaths.add("comments/" + id + "/status");
        }
        return store.readAllFiles(reviewId, streamPaths)
            .thenApply(blobs -> {
                try {
                    Map<String, AllCommentData> result = new LinkedHashMap<>();
                    for (String id : commentIds) {
                        result.put(id, new AllCommentData(
                            parseField(blobs.get("comments/" + id + "/metadata"), CommentMetadata.class),
                            parseField(blobs.get("comments/" + id + "/text"), CommentTextData.class),
                            parseField(blobs.get("comments/" + id + "/status"), CommentStatusData.class)
                        ));
                    }
                    return result;
                } catch (IOException e) {
                    throw new RuntimeException("readAllComments failed for " + reviewId, e);
                }
            });
    }

    // -------------------------------------------------------------------------
    // Batch metadata read
    // -------------------------------------------------------------------------

    /**
     * Reads all review metadata fields in a single {@code git cat-file --batch} call.
     */
    public CompletableFuture<ReviewMetadata> readAllMetadata(String reviewId) {
        List<String> streamPaths = List.of(
                "metadata/title", "metadata/description", "metadata/author",
                "metadata/primaryRepository", "metadata/branch", "metadata/baseBranch",
                "metadata/status", "reviewers", "metadata/repositoryActive"
        );

        return store.readAllFiles(reviewId, streamPaths)
                .thenApply(blobs -> {
                    try {
                        return new ReviewMetadata(
                                parseField(blobs.get(streamPaths.get(0)), String.class),
                                parseField(blobs.get(streamPaths.get(1)), String.class),
                                parseField(blobs.get(streamPaths.get(2)), String.class),
                                parseField(blobs.get(streamPaths.get(3)), String.class),
                                parseField(blobs.get(streamPaths.get(4)), String.class),
                                parseField(blobs.get(streamPaths.get(5)), String.class),
                                parseField(blobs.get(streamPaths.get(6)), String.class),
                                parseField(blobs.get(streamPaths.get(7)), ReviewerData.class),
                                parseField(blobs.get(streamPaths.get(8)), RepositoryActiveData.class)
                        );
                    } catch (IOException e) {
                        throw new RuntimeException("readAllMetadata failed for " + reviewId, e);
                    }
                });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private <T> CompletableFuture<Void> writeField(String reviewId, String streamPath,
                                                    String editor, T data) {
        StreamEntry<T> entry = StreamEntry.create(editor, data);
        byte[] bytes = NdjsonWriter.toBytes(entry);
        return store.writeFile(reviewId, streamPath, bytes);
    }

    private <T> CompletableFuture<List<StreamEntry<T>>> readField(String reviewId,
                                                                    String streamPath,
                                                                    Class<T> dataType) {
        return store.readFile(reviewId, streamPath).thenApply(opt -> {
            try {
                return NdjsonReader.fromBytes(opt.orElse(new byte[0]), dataType);
            } catch (IOException e) {
                LOGGER.warn("readField failed [{}/{}]: {}", reviewId, streamPath, e.getMessage());
                return List.of();
            }
        });
    }

    private <T> CompletableFuture<List<StreamEntry<T>>> readFieldWithType(String reviewId,
                                                                            String streamPath,
                                                                            Type type) {
        return store.readFile(reviewId, streamPath).thenApply(opt -> {
            try {
                return NdjsonReader.fromBytes(opt.orElse(new byte[0]), type);
            } catch (IOException e) {
                LOGGER.warn("readField failed [{}/{}]: {}", reviewId, streamPath, e.getMessage());
                return List.of();
            }
        });
    }

    private <T> List<StreamEntry<T>> parseField(Optional<byte[]> raw, Class<T> dataType) throws IOException {
        return NdjsonReader.fromBytes(raw.orElse(new byte[0]), dataType);
    }

    /**
     * Appends a single-value entry keyed by a logical reviewer name into the shared NDJSON
     * byte array stored in {@code files} for {@code streamPath}. Multiple calls for the same
     * stream path accumulate lines so that all entries survive in a single file.
     */
    private <T> void putMerged(Map<String, byte[]> files, String reviewId,
                                String streamPath, String editor, String key, T data) {
        String mapKey = REVIEWS_PREFIX + reviewId + "/" + streamPath;
        StreamEntry<T> entry = StreamEntry.create(key, data);
        byte[] newLine = NdjsonWriter.toBytes(entry);
        byte[] existing = files.get(mapKey);
        files.put(mapKey, appendNdjsonLine(existing != null ? existing : new byte[0], newLine));
    }

    /**
     * Appends {@code newLine} to {@code existing} NDJSON bytes, inserting a newline separator
     * when the existing content does not already end with one.
     */
    private byte[] appendNdjsonLine(byte[] existing, byte[] newLine) {
        if (existing.length == 0) {
            return newLine;
        }
        boolean endsWithNewline = existing[existing.length - 1] == '\n';
        int extraByte = endsWithNewline ? 0 : 1;
        byte[] combined = new byte[existing.length + extraByte + newLine.length];
        System.arraycopy(existing, 0, combined, 0, existing.length);
        if (!endsWithNewline) {
            combined[existing.length] = '\n';
        }
        System.arraycopy(newLine, 0, combined, existing.length + extraByte, newLine.length);
        return combined;
    }

    private <T> void put(Map<String, byte[]> files, String reviewId,
                         String streamPath, String editor, T data) {
        StreamEntry<T> entry = StreamEntry.create(editor, data);
        files.put(REVIEWS_PREFIX + reviewId + "/" + streamPath, NdjsonWriter.toBytes(entry));
    }

    /** Strips the {@code "reviews/<reviewId>/"} prefix to get the store-relative path. */
    private Map<String, byte[]> stripReviewPrefix(String reviewId, Map<String, byte[]> files) {
        String prefix = REVIEWS_PREFIX + reviewId + "/";
        Map<String, byte[]> stripped = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            String key = e.getKey();
            stripped.put(key.startsWith(prefix) ? key.substring(prefix.length()) : key, e.getValue());
        }
        return stripped;
    }

    private static final String REVIEWS_PREFIX = "reviews/";
}
