package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.git.GitReviewNotesManager;
import com.kalynx.serverlessreviewtool.git.ReviewNotesManagerFactory;
import com.kalynx.serverlessreviewtool.models.ReviewComment;
import com.kalynx.serverlessreviewtool.models.review.StreamEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Manages loading and saving review comments to and from git notes.
 * Handles all comment-level CRUD operations for a review, isolated from review context state.
 */
public class ReviewCommentManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewCommentManager.class);

    private final ReviewNotesManagerFactory notesManagerFactory;

    /**
     * Constructs a ReviewCommentManager with the given notes manager factory.
     *
     * @param notesManagerFactory factory for creating per-repository git notes managers
     */
    public ReviewCommentManager(ReviewNotesManagerFactory notesManagerFactory) {
        this.notesManagerFactory = notesManagerFactory;
    }

    /**
     * Load all comments for a review from the given primary repository.
     *
     * @param reviewId the review identifier
     * @param primaryRepoName the name of the primary repository containing the review notes
     * @return future completing with the list of loaded comments
     */
    public CompletableFuture<List<ReviewComment>> loadCommentsFromKnownRepository(
            String reviewId, String primaryRepoName) {
        GitReviewNotesManager notesManager = notesManagerFactory.create(primaryRepoName);
        long listCommentsStart = System.nanoTime();

        return notesManager.listCommentIds(reviewId)
            .thenCompose(commentIds -> {
                LOGGER.info("TIMING [{}] listCommentIds (repo={}): {}ms",
                    reviewId, primaryRepoName, elapsedMs(listCommentsStart));

                if (commentIds.isEmpty()) {
                    LOGGER.debug("No comments found for review: {}", reviewId);
                    return CompletableFuture.completedFuture(new ArrayList<ReviewComment>());
                }

                LOGGER.debug("Found {} comment threads for review: {}", commentIds.size(), reviewId);

                long loadCommentsStart = System.nanoTime();
                List<CompletableFuture<ReviewComment>> commentFutures = commentIds.stream()
                    .map(commentId -> loadSingleComment(notesManager, reviewId, commentId))
                    .toList();

                return CompletableFuture.allOf(commentFutures.toArray(new CompletableFuture[0]))
                    .thenApply(ignored -> {
                        List<ReviewComment> comments = commentFutures.stream()
                            .map(CompletableFuture::join)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                        LOGGER.info("TIMING [{}] loadComments ({} comments, parallel): {}ms",
                            reviewId, comments.size(), elapsedMs(loadCommentsStart));
                        return comments;
                    });
            })
            .exceptionally(error -> {
                LOGGER.error("Failed to load comments from repo {} for review {}: {}", primaryRepoName, reviewId, error.getMessage());
                return new ArrayList<>();
            });
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

        LOGGER.debug("Saving comment for review: {} (id: {})", reviewId, comment.getId());

        GitReviewNotesManager notesManager = notesManagerFactory.create(repositoryName);
        String commentType = comment.needsResolution() ? "review" : "comment";

        CompletableFuture<Void> metadataFuture = notesManager.writeCommentMetadata(
            reviewId, comment.getId(), comment.getAuthor(),
            comment.getFilePath(), comment.getLineNumber(), comment.getLineNumber(), null
        );
        CompletableFuture<Void> textFuture = notesManager.writeCommentText(
            reviewId, comment.getId(), comment.getAuthor(),
            comment.getText(), comment.getParentId(), commentType
        );

        CompletableFuture<Void> saveFuture;
        if (comment.needsResolution() || comment.isResolved()) {
            CompletableFuture<Void> statusFuture = notesManager.writeCommentStatus(
                reviewId, comment.getId(), comment.getAuthor(),
                comment.needsResolution(), comment.isResolved()
            );
            saveFuture = CompletableFuture.allOf(metadataFuture, textFuture, statusFuture);
        } else {
            saveFuture = CompletableFuture.allOf(metadataFuture, textFuture);
        }

        return saveFuture
            .thenRun(() -> LOGGER.debug("Comment saved successfully for review: {} (id: {})", reviewId, comment.getId()))
            .exceptionally(error -> {
                LOGGER.error("Failed to save comment for review: {} (id: {})", reviewId, comment.getId(), error);
                return null;
            });
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

        GitReviewNotesManager notesManager = notesManagerFactory.create(repositoryName);

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
            GitReviewNotesManager notesManager, String reviewId, String commentId) {

        CompletableFuture<List<StreamEntry<GitReviewNotesManager.CommentMetadata>>> metadataFuture =
            notesManager.readCommentMetadata(reviewId, commentId);
        CompletableFuture<List<StreamEntry<GitReviewNotesManager.CommentTextData>>> textFuture =
            notesManager.readCommentText(reviewId, commentId);
        CompletableFuture<List<StreamEntry<GitReviewNotesManager.CommentStatusData>>> statusFuture =
            notesManager.readCommentStatus(reviewId, commentId);

        return CompletableFuture.allOf(metadataFuture, textFuture, statusFuture)
            .thenApply(ignored -> {
                List<StreamEntry<GitReviewNotesManager.CommentMetadata>> metadata = metadataFuture.join();
                List<StreamEntry<GitReviewNotesManager.CommentTextData>> textEntries = textFuture.join();
                List<StreamEntry<GitReviewNotesManager.CommentStatusData>> statusEntries = statusFuture.join();

                if (metadata.isEmpty() || textEntries.isEmpty()) {
                    return null;
                }

                StreamEntry<GitReviewNotesManager.CommentMetadata> latestMetadata = metadata.getLast();
                StreamEntry<GitReviewNotesManager.CommentTextData> firstText = textEntries.getFirst();

                GitReviewNotesManager.CommentMetadata metaData = latestMetadata.data();
                GitReviewNotesManager.CommentTextData textData = firstText.data();

                ReviewComment comment = new ReviewComment(
                    commentId,
                    metaData.file(),
                    metaData.line(),
                    firstText.editor(),
                    textData.text(),
                    firstText.timestamp().toString(),
                    textData.replyTo(),
                    "review".equals(textData.type())
                );

                if (!statusEntries.isEmpty()) {
                    StreamEntry<GitReviewNotesManager.CommentStatusData> latestStatus = statusEntries.getLast();
                    GitReviewNotesManager.CommentStatusData statusData = latestStatus.data();

                    if (statusData.needsResolution() != null) {
                        comment.setNeedsResolution(statusData.needsResolution());
                    }

                    if (statusData.resolved() != null) {
                        if (statusData.resolved()) {
                            comment.markResolved(latestStatus.editor());
                        } else {
                            comment.markUnresolved();
                        }
                    }
                }

                return comment;
            })
            .exceptionally(error -> {
                LOGGER.error("Failed to load comment {}: {}", commentId, error.getMessage());
                return null;
            });
    }

    private static long elapsedMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }
}
