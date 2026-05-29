package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.git.OrphanBranchReviewManager;
import com.kalynx.serverlessreviewtool.git.ReviewBranchManagerFactory;
import com.kalynx.serverlessreviewtool.indexer.CommentIndexerClient;
import com.kalynx.serverlessreviewtool.indexer.CommentRoutingEntry;
import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.serverlessreviewtool.models.ReviewComment;
import com.kalynx.serverlessreviewtool.models.review.StreamEntry;
import com.kalynx.swingtheme.theme.LoadingStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Manages loading and saving review comments via the orphan branch store.
 * Handles all comment-level CRUD operations for a review, isolated from review context state.
 */
public class ReviewCommentManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewCommentManager.class);

    private final ReviewBranchManagerFactory branchManagerFactory;
    private final CommentIndexerClient commentIndexerClient;
    private final RepositoryManager repositoryManager;

    public ReviewCommentManager(ReviewBranchManagerFactory branchManagerFactory,
                                CommentIndexerClient commentIndexerClient,
                                RepositoryManager repositoryManager) {
        this.branchManagerFactory = branchManagerFactory;
        this.commentIndexerClient = commentIndexerClient;
        this.repositoryManager = repositoryManager;
    }

    /**
     * Load all comments for a review. Queries the indexer for routing first; if the indexer is
     * not configured or returns no entries, falls back to a direct scan of all known repositories.
     *
     * @param reviewId the review identifier
     * @return future completing with the merged list of comments across all repositories
     */
    public CompletableFuture<List<ReviewComment>> loadAllComments(String reviewId) {
        if (reviewId == null || reviewId.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        List<CommentRoutingEntry> routing;
        try {
            routing = commentIndexerClient.getCommentRouting(reviewId);
        } catch (Exception e) {
            LOGGER.warn("Failed to get comment routing from indexer for review {}: {}", reviewId, e.getMessage());
            routing = List.of();
        }

        if (!routing.isEmpty()) {
            return loadFromRouting(reviewId, routing);
        }

        LOGGER.debug("No comment routing from indexer for review {}, falling back to direct scan", reviewId);
        return loadFromKnownRepositories(reviewId);
    }

    private CompletableFuture<List<ReviewComment>> loadFromRouting(
            String reviewId, List<CommentRoutingEntry> routing) {
        Map<String, List<CommentRoutingEntry>> byRepo = routing.stream()
            .collect(Collectors.groupingBy(CommentRoutingEntry::repositoryUrl));

        List<CompletableFuture<List<ReviewComment>>> repoFutures = byRepo.entrySet().stream()
            .map(entry -> {
                String repoUrl = entry.getKey();
                List<CommentRoutingEntry> entries = entry.getValue();
                String repoName = findRepoNameByUrl(repoUrl);
                if (repoName == null) {
                    LOGGER.warn("No repository found for URL: {}, skipping {} comment(s)", repoUrl, entries.size());
                    return CompletableFuture.completedFuture(List.<ReviewComment>of());
                }
                OrphanBranchReviewManager notesManager = branchManagerFactory.create(repoName);
                List<String> commentIds = entries.stream().map(CommentRoutingEntry::commentId).toList();
                return notesManager.readAllComments(reviewId, commentIds)
                    .thenApply(allData -> allData.entrySet().stream()
                        .map(e -> buildCommentFromData(e.getKey(), e.getValue()))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList()));
            })
            .toList();

        return CompletableFuture.allOf(repoFutures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> repoFutures.stream()
                .flatMap(f -> f.join().stream())
                .collect(Collectors.toList()))
            .exceptionally(error -> {
                LOGGER.error("Failed to load comments from routing for review {}: {}", reviewId, error.getMessage());
                return new ArrayList<>();
            });
    }

    private CompletableFuture<List<ReviewComment>> loadFromKnownRepositories(String reviewId) {
        List<Repository> repos = repositoryManager.getRepositories();
        if (repos.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        List<CompletableFuture<List<ReviewComment>>> repoFutures = repos.stream()
            .map(repo -> {
                OrphanBranchReviewManager notesManager = branchManagerFactory.create(repo.getName());
                return notesManager.listCommentIds(reviewId)
                    .thenCompose(commentIds -> {
                        if (commentIds.isEmpty()) return CompletableFuture.completedFuture(List.<ReviewComment>of());
                        return notesManager.readAllComments(reviewId, commentIds)
                            .thenApply(allData -> allData.entrySet().stream()
                                .map(e -> buildCommentFromData(e.getKey(), e.getValue()))
                                .filter(Objects::nonNull)
                                .collect(Collectors.toList()));
                    })
                    .exceptionally(e -> {
                        LOGGER.debug("No comment ids found in repo {} for review {}", repo.getName(), reviewId);
                        return List.of();
                    });
            })
            .toList();

        return CompletableFuture.allOf(repoFutures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> repoFutures.stream()
                .flatMap(f -> f.join().stream())
                .collect(Collectors.toList()))
            .exceptionally(error -> {
                LOGGER.error("Failed to scan repositories for comments for review {}: {}", reviewId, error.getMessage());
                return new ArrayList<>();
            });
    }

    /**
     * Reload a single comment by reading all three sub-streams from the named repository.
     * Used by SSE event handlers to refresh a specific comment without a full reload.
     *
     * @param reviewId the review identifier
     * @param repositoryUrl the URL of the repository containing the comment
     * @param commentId the comment identifier
     * @return future completing with the reloaded comment, or null if not found
     */
    public CompletableFuture<ReviewComment> reloadComment(String reviewId, String repositoryUrl, String commentId) {
        if (reviewId == null || reviewId.isEmpty() || commentId == null) {
            return CompletableFuture.completedFuture(null);
        }

        String repoName = repositoryUrl != null ? findRepoNameByUrl(repositoryUrl) : null;
        if (repoName != null) {
            return loadSingleComment(branchManagerFactory.create(repoName), reviewId, commentId);
        }

        LOGGER.debug("No repository matched URL '{}', scanning all repos for comment {}", repositoryUrl, commentId);
        return findCommentInAnyRepository(reviewId, commentId);
    }

    private CompletableFuture<ReviewComment> findCommentInAnyRepository(String reviewId, String commentId) {
        List<Repository> repos = repositoryManager.getRepositories();
        if (repos.isEmpty()) return CompletableFuture.completedFuture(null);

        List<CompletableFuture<ReviewComment>> futures = repos.stream()
            .map(repo -> loadSingleComment(branchManagerFactory.create(repo.getName()), reviewId, commentId))
            .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null));
    }

    /**
     * Save a single comment to the git notes of the specified repository.
     * All independent streams (metadata, text, status) are written in parallel.
     *
     * @param reviewId the review identifier
     * @param repositoryName the name of the repository to write the comment to
     * @param comment the comment to save
     * @return future completing when the comment is saved
     */
    public CompletableFuture<Void> saveComment(String reviewId, String repositoryName, ReviewComment comment) {
        if (reviewId == null || reviewId.isEmpty() || repositoryName == null || comment == null) {
            return CompletableFuture.completedFuture(null);
        }
        LoadingStateManager.getInstance().startLoading("Saving Comment");
        LOGGER.debug("Saving comment for review: {} (id: {})", reviewId, comment.getId());

        OrphanBranchReviewManager notesManager = branchManagerFactory.create(repositoryName);
        String commentType = comment.needsResolution() ? "review" : "comment";

        OrphanBranchReviewManager.CommentMetadata metadata =
            new OrphanBranchReviewManager.CommentMetadata(
                comment.getFilePath(), comment.getLineNumber(), comment.getLineNumber(), null);
        OrphanBranchReviewManager.CommentTextData text =
            new OrphanBranchReviewManager.CommentTextData(
                comment.getText(), comment.getParentId(), commentType);
        OrphanBranchReviewManager.CommentStatusData status =
            (comment.needsResolution() || comment.isResolved())
                ? new OrphanBranchReviewManager.CommentStatusData(
                        comment.needsResolution(), comment.isResolved())
                : null;

        return notesManager.writeComment(reviewId, comment.getId(), comment.getAuthor(),
                    metadata, text, status)
            .thenRun(() -> LOGGER.debug("Comment saved successfully for review: {} (id: {})", reviewId, comment.getId()))
            .exceptionally(error -> {
                LOGGER.error("Failed to save comment for review: {} (id: {})", reviewId, comment.getId(), error);
                return null;
            }).whenComplete((ignored, error) -> LoadingStateManager.getInstance().stopLoading("Saving Comment"));
    }

    /**
     * Resolve or unresolve a comment by writing only the status stream.
     * Use this instead of {@link #saveComment} when only the resolved state has changed,
     * to avoid redundant metadata and text writes.
     *
     * @param reviewId the review identifier
     * @param repositoryName the name of the repository containing the review notes
     * @param comment the comment whose resolution state has changed
     * @return future completing when the status stream has been written
     */
    public CompletableFuture<Void> resolveComment(String reviewId, String repositoryName, ReviewComment comment) {
        if (reviewId == null || reviewId.isEmpty() || repositoryName == null || comment == null) {
            return CompletableFuture.completedFuture(null);
        }

        LOGGER.debug("Resolving comment for review: {} (id: {})", reviewId, comment.getId());

        OrphanBranchReviewManager notesManager = branchManagerFactory.create(repositoryName);

        return notesManager.writeCommentStatus(
            reviewId, comment.getId(), comment.getAuthor(),
            comment.needsResolution(), comment.isResolved()
        )
            .thenRun(() -> LOGGER.debug("Comment resolution written for review: {} (id: {})", reviewId, comment.getId()))
            .exceptionally(error -> {
                LOGGER.error("Failed to write comment resolution for review: {} (id: {})", reviewId, comment.getId(), error);
                return null;
            });
    }

    /**
     * Writes the status stream for every comment in {@code comments} in a single commit+push.
     * Use this instead of {@link #saveAllComments} when only resolution state has changed,
     * to avoid N separate pushes (and N SSE events) for a batch resolve/unresolve action.
     *
     * @param reviewId       the review identifier
     * @param repositoryName the repository containing the review notes
     * @param comments       comments whose resolution state has changed
     * @return future completing when all status streams are written in one push
     */
    public CompletableFuture<Void> resolveAllComments(String reviewId, String repositoryName,
                                                       List<ReviewComment> comments) {
        if (reviewId == null || reviewId.isEmpty() || repositoryName == null
                || comments == null || comments.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        LOGGER.debug("Batch resolving {} comments for review: {}", comments.size(), reviewId);

        OrphanBranchReviewManager notesManager = branchManagerFactory.create(repositoryName);
        String editor = comments.getFirst().getAuthor();

        List<OrphanBranchReviewManager.CommentStatusEntry> entries = comments.stream()
            .map(c -> new OrphanBranchReviewManager.CommentStatusEntry(
                    c.getId(), c.needsResolution(), c.isResolved()))
            .toList();

        return notesManager.writeAllCommentStatuses(reviewId, editor, entries)
            .thenRun(() -> LOGGER.debug("Batch resolve written for {} comments in review: {}",
                    comments.size(), reviewId))
            .exceptionally(error -> {
                LOGGER.error("Failed to batch-resolve comments for review: {}", reviewId, error);
                return null;
            });
    }

    /**
     * Save all provided comments for a review to git notes in the specified repository.
     *
     * @param reviewId the review identifier
     * @param repositoryName the name of the repository to write the comments to
     * @param comments the list of comments to save
     * @return future completing when all comments are saved
     */
    public CompletableFuture<Void> saveAllComments(String reviewId, String repositoryName, List<ReviewComment> comments) {
        if (reviewId == null || reviewId.isEmpty() || comments == null || comments.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        LOGGER.debug("Batch saving {} comments for review: {}", comments.size(), reviewId);

        List<CompletableFuture<Void>> saveFutures = comments.stream()
            .map(comment -> saveComment(reviewId, repositoryName, comment))
            .toList();

        return CompletableFuture.allOf(saveFutures.toArray(new CompletableFuture[0]))
            .thenRun(() -> LOGGER.debug("All {} comments batch-saved successfully for review: {}", comments.size(), reviewId))
            .exceptionally(error -> {
                LOGGER.error("Failed to batch-save comments for review: {}", reviewId, error);
                return null;
            });
    }

    private CompletableFuture<ReviewComment> loadSingleComment(
            OrphanBranchReviewManager notesManager, String reviewId, String commentId) {
        return notesManager.readAllComments(reviewId, List.of(commentId))
            .thenApply(allData -> buildCommentFromData(commentId, allData.get(commentId)))
            .exceptionally(error -> {
                LOGGER.error("Failed to load comment {}: {}", commentId, error.getMessage());
                return null;
            });
    }

    private ReviewComment buildCommentFromData(String commentId,
            OrphanBranchReviewManager.AllCommentData data) {
        if (data == null) return null;
        List<StreamEntry<OrphanBranchReviewManager.CommentMetadata>> metadata = data.metadata();
        List<StreamEntry<OrphanBranchReviewManager.CommentTextData>> textEntries = data.text();
        List<StreamEntry<OrphanBranchReviewManager.CommentStatusData>> statusEntries = data.status();

        if (metadata.isEmpty() || textEntries.isEmpty()) return null;

        StreamEntry<OrphanBranchReviewManager.CommentMetadata> latestMetadata = metadata.getLast();
        StreamEntry<OrphanBranchReviewManager.CommentTextData> firstText = textEntries.getFirst();
        OrphanBranchReviewManager.CommentMetadata metaData = latestMetadata.data();
        OrphanBranchReviewManager.CommentTextData textData = firstText.data();

        ReviewComment comment = new ReviewComment(
            commentId, metaData.file(), metaData.line(), firstText.editor(),
            textData.text(), firstText.timestamp().toString(),
            textData.replyTo(), "review".equals(textData.type())
        );

        if (!statusEntries.isEmpty()) {
            StreamEntry<OrphanBranchReviewManager.CommentStatusData> latestStatus = statusEntries.getLast();
            OrphanBranchReviewManager.CommentStatusData statusData = latestStatus.data();
            if (statusData.needsResolution() != null) comment.setNeedsResolution(statusData.needsResolution());
            if (statusData.resolved() != null) {
                if (statusData.resolved()) comment.markResolved(latestStatus.editor());
                else comment.markUnresolved();
            }
        }
        return comment;
    }

    private String findRepoNameByUrl(String url) {
        if (url == null) return null;
        return repositoryManager.getRepositories().stream()
            .filter(r -> url.equals(r.getUrl()))
            .findFirst()
            .map(Repository::getName)
            .orElse(null);
    }

    private static long elapsedMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }
}
