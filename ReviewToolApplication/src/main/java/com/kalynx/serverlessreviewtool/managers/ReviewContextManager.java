package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.git.OrphanBranchReviewManager;
import com.kalynx.serverlessreviewtool.git.ReviewBranchManagerFactory;
import com.kalynx.serverlessreviewtool.models.ReviewContext;
import com.kalynx.serverlessreviewtool.models.ReviewFile;
import com.kalynx.serverlessreviewtool.models.ReviewerInfo;
import com.kalynx.serverlessreviewtool.models.ReviewerStatus;
import com.kalynx.serverlessreviewtool.models.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * ReviewContextManager manages the lifecycle of a ReviewContext.
 * Responsibilities:
 * - Maintain the current ReviewContext and notify listeners when it changes
 * - Delegate metadata loading to ReviewMetadataLoader
 * - Delegate reviewer operations to ReviewerManager
 * - Persist review metadata changes via the orphan branch store
 * - Provide comment and file-change delegation to ReviewCommentManager and ReviewChangeSetManager
 */
public class ReviewContextManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewContextManager.class);

    private ReviewContext currentReviewContext;
    private final ReviewBranchManagerFactory branchManagerFactory;
    private final ReviewCommentManager commentManager;
    private final ReviewChangeSetManager changeSetManager;
    private final ReviewMetadataLoader metadataLoader;
    private final ReviewerManager reviewerManager;
    private final Set<Consumer<ReviewContext>> listeners = new HashSet<>();

    /**
     * Constructs a ReviewContextManager with its required collaborators.
     *
     * @param git the git client
     * @param repositoryManager the repository manager
     * @param commentManager the comment manager for comment I/O
     * @param changeSetManager the change set manager for file/commit operations
     */
    public ReviewContextManager(RepositoryManager repositoryManager,
                                ReviewCommentManager commentManager, ReviewChangeSetManager changeSetManager,
                                ReviewBranchManagerFactory branchManagerFactory) {
        this.branchManagerFactory = branchManagerFactory;
        this.commentManager = commentManager;
        this.changeSetManager = changeSetManager;
        this.metadataLoader = new ReviewMetadataLoader(branchManagerFactory, repositoryManager, commentManager, this::getReviewContext);
        this.reviewerManager = new ReviewerManager(branchManagerFactory);
    }

    /**
     * Package-private constructor for unit testing, accepting all collaborators directly.
     */
    ReviewContextManager(ReviewBranchManagerFactory branchManagerFactory,
                         ReviewCommentManager commentManager,
                         ReviewChangeSetManager changeSetManager,
                         ReviewMetadataLoader metadataLoader,
                         ReviewerManager reviewerManager) {
        this.branchManagerFactory = branchManagerFactory;
        this.commentManager = commentManager;
        this.changeSetManager = changeSetManager;
        this.metadataLoader = metadataLoader;
        this.reviewerManager = reviewerManager;
    }

    /**
     * Save a comment to git notes using the primary repository from the current review context.
     *
     * @param reviewId the review identifier
     * @param comment the comment to save
     * @return future that completes when comment is saved
     */
    public CompletableFuture<Void> saveComment(String reviewId, com.kalynx.serverlessreviewtool.models.ReviewComment comment) {
        if (reviewId == null || reviewId.isEmpty() || comment == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (currentReviewContext == null || currentReviewContext.repositories.isEmpty()) {
            LOGGER.warn("No review context or repositories, cannot save comment");
            return CompletableFuture.completedFuture(null);
        }
        return commentManager.saveComment(reviewId, currentReviewContext.repositories.getFirst().getName(), comment);
    }

    /**
     * Writes only the status stream for each comment in a single commit+push.
     * Use this instead of {@link #saveAllComments} when only resolution state has changed.
     */
    public CompletableFuture<Void> resolveAllComments(String reviewId,
                                                       List<com.kalynx.serverlessreviewtool.models.ReviewComment> comments) {
        if (reviewId == null || reviewId.isEmpty() || comments == null || comments.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        if (currentReviewContext == null || currentReviewContext.repositories.isEmpty()) {
            LOGGER.warn("No review context or repositories, cannot resolve comments");
            return CompletableFuture.completedFuture(null);
        }
        return commentManager.resolveAllComments(reviewId, currentReviewContext.repositories.getFirst().getName(), comments);
    }

    /**
     * Save all comments for a review to git notes.
     *
     * @param reviewId the review identifier
     * @param comments the comments to save
     * @return future that completes when all comments are saved
     */
    public CompletableFuture<Void> saveAllComments(String reviewId, List<com.kalynx.serverlessreviewtool.models.ReviewComment> comments) {
        if (reviewId == null || reviewId.isEmpty() || comments == null || comments.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        if (currentReviewContext == null || currentReviewContext.repositories.isEmpty()) {
            LOGGER.warn("No review context or repositories, cannot save comments");
            return CompletableFuture.completedFuture(null);
        }
        return commentManager.saveAllComments(reviewId, currentReviewContext.repositories.getFirst().getName(), comments);
    }

    /**
     * Load files changed in a review by comparing branch against base branch.
     *
     * @param repositoryName the repository containing the review
     * @param reviewBranch the branch being reviewed
     * @param baseBranch the base branch to compare against
     * @return future that completes with list of ReviewFile objects
     */
    public CompletableFuture<List<ReviewFile>> loadFilesForReview(
            String repositoryName, String reviewBranch, String baseBranch) {
        return changeSetManager.loadFilesForReview(repositoryName, reviewBranch, baseBranch);
    }

    /**
     * Load files changed in a review by comparing the already-known branches across all given repositories.
     *
     * @param repositories list of repositories containing the review
     * @param branch the review branch
     * @param baseBranch the base branch to compare against
     * @return future that completes with list of ReviewFile objects across all repositories
     */
    public CompletableFuture<List<ReviewFile>> loadFilesFromReviewCommits(
            List<Repository> repositories, String branch, String baseBranch) {
        return changeSetManager.loadFilesFromReviewCommits(repositories, branch, baseBranch);
    }

    /**
     * Load files changed in a review using stored commit snapshots.
     *
     * @param reviewId the review identifier
     * @param repositories list of repositories containing the review
     * @param branch the review branch
     * @param baseBranch the base branch
     * @param commitsByRepository map of repository name to stored commit hashes
     * @return future that completes with list of ReviewFile objects
     */
    public CompletableFuture<List<ReviewFile>> loadFilesFromStoredReviewCommits(
            String reviewId, List<Repository> repositories, String branch,
            String baseBranch, Map<String, List<String>> commitsByRepository) {
        return changeSetManager.loadFilesFromStoredReviewCommits(reviewId, repositories, branch, baseBranch, commitsByRepository);
    }

    /**
     * Capture commit snapshots for each repository participating in a review.
     *
     * @param reviewId the review identifier
     * @param repositories list of repositories participating in the review
     * @param reviewBranch the review branch
     * @param baseBranch the base branch
     * @param editor the user triggering the capture
     * @return future completing with a map of repository name to captured commit hashes
     */
    public CompletableFuture<Map<String, List<String>>> captureReviewCommitSnapshots(
            String reviewId, List<Repository> repositories, String reviewBranch,
            String baseBranch, String editor) {
        return changeSetManager.captureReviewCommitSnapshots(reviewId, repositories, reviewBranch, baseBranch, editor);
    }

    /**
     * Load the latest stored commit snapshot for a review in a given repository.
     *
     * @param reviewId the review identifier
     * @param repositoryName the name of the repository
     * @return future completing with the list of stored commit hashes
     */
    public CompletableFuture<List<String>> loadLatestReviewCommits(String reviewId, String repositoryName) {
        return changeSetManager.loadLatestReviewCommits(reviewId, repositoryName);
    }

    /**
     * Read-only load of stored commit snapshots across the given repositories. Returns
     * a map of repository name to stored commit hashes (empty list when none are stored).
     *
     * @param reviewId     the review identifier
     * @param repositories the repositories participating in the review
     * @return future that completes with the per-repository commit snapshots
     */
    public CompletableFuture<Map<String, List<String>>> loadStoredReviewCommitsForAllRepositories(
            String reviewId, List<Repository> repositories) {
        return changeSetManager.loadStoredReviewCommitsForAllRepositories(reviewId, repositories);
    }

    /**
     * Load review metadata by searching across all known repositories.
     *
     * @param reviewId the review identifier
     * @return future that completes when ReviewContext is created and set
     */
    public CompletableFuture<ReviewContext> loadReviewMetadata(String reviewId) {
        return metadataLoader.loadReviewMetadata(reviewId).thenApply(this::applyContext);
    }

    /**
     * Load review metadata without reloading comments.
     *
     * @param reviewId the review identifier
     * @return future that completes when ReviewContext metadata is refreshed
     */
    public CompletableFuture<ReviewContext> loadReviewMetadataOnly(String reviewId) {
        return metadataLoader.loadReviewMetadataOnly(reviewId).thenApply(this::applyContext);
    }

    /**
     * Load review metadata from specific repositories.
     *
     * @param reviewId the review identifier
     * @param repositoryNames list of repository names that contain the review
     * @return future that completes when ReviewContext is created and set
     */
    public CompletableFuture<ReviewContext> loadReviewMetadata(String reviewId, List<String> repositoryNames) {
        return metadataLoader.loadReviewMetadata(reviewId, repositoryNames).thenApply(this::applyContext);
    }

    /**
     * Load review metadata from specific repositories without reloading comments.
     *
     * @param reviewId the review identifier
     * @param repositoryNames list of repository names that contain the review
     * @return future that completes when ReviewContext metadata is refreshed
     */
    public CompletableFuture<ReviewContext> loadReviewMetadataOnly(String reviewId, List<String> repositoryNames) {
        return metadataLoader.loadReviewMetadataOnly(reviewId, repositoryNames).thenApply(this::applyContext);
    }

    /**
     * Load review metadata from specific repositories with a pre-known primary repository.
     *
     * @param reviewId the review identifier
     * @param repositoryNames list of repository names that contain the review
     * @param knownPrimaryRepoName primary repository name from the cached ReviewItem
     * @return future that completes when ReviewContext is created and set
     */
    public CompletableFuture<ReviewContext> loadReviewMetadata(String reviewId, List<String> repositoryNames, String knownPrimaryRepoName) {
        return metadataLoader.loadReviewMetadata(reviewId, repositoryNames, knownPrimaryRepoName).thenApply(this::applyContext);
    }

    /**
     * Load review metadata from specific repositories with a pre-known primary repository, without reloading comments.
     *
     * @param reviewId the review identifier
     * @param repositoryNames list of repository names that contain the review
     * @param knownPrimaryRepoName primary repository name
     * @return future that completes when ReviewContext metadata is refreshed
     */
    public CompletableFuture<ReviewContext> loadReviewMetadataOnly(String reviewId, List<String> repositoryNames, String knownPrimaryRepoName) {
        return metadataLoader.loadReviewMetadataOnly(reviewId, repositoryNames, knownPrimaryRepoName).thenApply(this::applyContext);
    }

    /**
     * Set the current ReviewContext and notify all listeners.
     *
     * @param reviewContext the new review context
     */
    public void setReviewContext(ReviewContext reviewContext) {
        this.currentReviewContext = reviewContext;
        LOGGER.debug("ReviewContext set: {}", reviewContext != null ? reviewContext.getReviewId() : "null");
        notifyListeners();
    }

    /**
     * Save review metadata to git notes using a parallel batch write.
     *
     * @param reviewContext the updated review context to persist
     * @return future that completes when all metadata has been written and pushed
     */
    public CompletableFuture<Void> saveReviewMetadata(ReviewContext reviewContext) {
        if (reviewContext == null || reviewContext.reviewId == null || reviewContext.reviewId.isEmpty()) {
            LOGGER.warn("Cannot save review - invalid review context");
            return CompletableFuture.completedFuture(null);
        }

        LOGGER.debug("Saving review metadata for review: {}", reviewContext.reviewId);

        if (reviewContext.repositories.isEmpty()) {
            LOGGER.warn("No repositories in review context, cannot save");
            return CompletableFuture.completedFuture(null);
        }

        Repository primaryRepo = reviewContext.repositories.getFirst();
        OrphanBranchReviewManager notesManager = branchManagerFactory.create(primaryRepo.getName());
        String editor = reviewContext.author != null ? reviewContext.author : "system";

        List<Map.Entry<String, com.kalynx.serverlessreviewtool.models.review.ReviewerData>> reviewerEntries = new ArrayList<>();

        for (ReviewerInfo reviewer : reviewContext.reviewers) {
            reviewerEntries.add(Map.entry(
                reviewer.getName(),
                new com.kalynx.serverlessreviewtool.models.review.ReviewerData(
                    reviewer.getStatus().name().toLowerCase(), "")));
        }

        if (currentReviewContext != null) {
            Set<String> newReviewerNames = reviewContext.reviewers.stream()
                .map(ReviewerInfo::getName)
                .collect(Collectors.toSet());

            for (ReviewerInfo previousReviewer : currentReviewContext.reviewers) {
                if (!newReviewerNames.contains(previousReviewer.getName())) {
                    LOGGER.debug("Writing LEFT status for removed reviewer {} on review {}",
                        previousReviewer.getName(), reviewContext.reviewId);
                    reviewerEntries.add(Map.entry(
                        previousReviewer.getName(),
                        new com.kalynx.serverlessreviewtool.models.review.ReviewerData("left", "")));
                }
            }
        }

        return notesManager.saveAllMetadataBatch(
                reviewContext.reviewId,
                editor,
                reviewContext.title,
                reviewContext.summary,
                reviewContext.author,
                reviewContext.status.name(),
                reviewerEntries)
            .thenCompose(ignored -> saveRepositoryActiveStates(
                notesManager, reviewContext.reviewId, editor,
                reviewContext.repositories,
                currentReviewContext != null ? currentReviewContext.repositories : List.of()))
            .thenRun(() -> {
                LOGGER.debug("Review metadata saved successfully for review: {}", reviewContext.reviewId);
                setReviewContext(reviewContext);
            })
            .exceptionally(error -> {
                LOGGER.error("Failed to save review metadata for review: " + reviewContext.reviewId, error);
                throw new RuntimeException("Failed to save review metadata", error);
            });
    }

    private CompletableFuture<Void> saveRepositoryActiveStates(
            OrphanBranchReviewManager notesManager, String reviewId, String editor,
            List<Repository> newRepositories, List<Repository> previousRepositories) {

        Set<String> newRepoNames = newRepositories.stream()
            .map(Repository::getName)
            .collect(Collectors.toSet());
        Set<String> previousRepoNames = previousRepositories.stream()
            .map(Repository::getName)
            .collect(Collectors.toSet());

        if (newRepoNames.equals(previousRepoNames)) {
            return CompletableFuture.completedFuture(null);
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String repoName : previousRepoNames) {
            if (!newRepoNames.contains(repoName)) {
                LOGGER.debug("Writing inactive flag for repository {} on review {}", repoName, reviewId);
                futures.add(notesManager.writeRepositoryActive(reviewId, repoName, editor, false));
            }
        }

        for (String repoName : newRepoNames) {
            if (!previousRepoNames.contains(repoName)) {
                LOGGER.debug("Writing active flag for repository {} on review {}", repoName, reviewId);
                futures.add(notesManager.writeRepositoryActive(reviewId, repoName, editor, true));
            }
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
    }

    /**
     * Get the current ReviewContext.
     *
     * @return the current review context, or null if none is loaded
     */
    public ReviewContext getReviewContext() {
        return currentReviewContext;
    }

    /**
     * Add a listener for ReviewContext changes. The listener is immediately called with the current context.
     *
     * @param listener the listener to add
     */
    public void addListener(Consumer<ReviewContext> listener) {
        listeners.add(listener);
        listener.accept(currentReviewContext);
    }

    /**
     * Remove a previously added ReviewContext listener.
     *
     * @param listener the listener to remove
     */
    @SuppressWarnings("unused")
    public void removeListener(Consumer<ReviewContext> listener) {
        listeners.remove(listener);
    }

    /**
     * Add a reviewer to a review in the specified repositories.
     *
     * @param reviewId the review identifier
     * @param reviewerName the name of the reviewer to add
     * @param repositoryNames the repositories containing the review notes
     * @return future completing when the reviewer is written
     */
    public CompletableFuture<Void> addReviewer(String reviewId, String reviewerName, List<String> repositoryNames) {
        return reviewerManager.addReviewer(reviewId, reviewerName, repositoryNames);
    }

    /**
     * Update a reviewer's decision status for a review.
     *
     * @param reviewId the review identifier
     * @param reviewerName reviewer/editor name
     * @param reviewerStatus target decision status
     * @param repositoryNames repositories containing the review notes
     * @return future completing when reviewer status is written
     */
    public CompletableFuture<Void> updateReviewerStatus(
            String reviewId, String reviewerName, ReviewerStatus reviewerStatus, List<String> repositoryNames) {
        return reviewerManager.updateReviewerStatus(reviewId, reviewerName, reviewerStatus, repositoryNames);
    }

    /**
     * Remove a reviewer from a review.
     *
     * @param reviewId the review identifier
     * @param reviewerName the name of the reviewer to remove
     * @param repositoryNames the repositories containing the review notes
     * @return future completing when the reviewer removal is written
     */
    public CompletableFuture<Void> removeReviewer(String reviewId, String reviewerName, List<String> repositoryNames) {
        return reviewerManager.removeReviewer(reviewId, reviewerName, repositoryNames);
    }

    /**
     * Adds secondary repository references for an existing review. Writes git notes to each new
     * repository, so they are discoverable as part of the review on next load.
     *
     * @param reviewContext the current review context, used to derive primary repo, branch info, and author
     * @param newRepoNames names of the repositories to add as secondary references
     * @return future that completes when all secondary references have been written and pushed
     */
    public CompletableFuture<Void> addSecondaryRepositories(ReviewContext reviewContext, List<String> newRepoNames) {
        if (newRepoNames == null || newRepoNames.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        if (reviewContext == null || reviewContext.repositories.isEmpty()) {
            LOGGER.warn("No review context or repositories, cannot add secondary repositories");
            return CompletableFuture.completedFuture(null);
        }

        String primaryRepoName = reviewContext.repositories.getFirst().getName();
        String branch = reviewContext.getBranch() != null ? reviewContext.getBranch() : "";
        String baseBranch = reviewContext.getBaseBranch() != null ? reviewContext.getBaseBranch() : "";
        String editor = reviewContext.author != null ? reviewContext.author : "system";

        List<CompletableFuture<Void>> futures = newRepoNames.stream()
            .map(repoName -> branchManagerFactory.create(repoName)
                .createSecondaryReviewReference(reviewContext.reviewId, editor, List.of(), branch, baseBranch))
            .toList();
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private ReviewContext applyContext(ReviewContext ctx) {
        if (ctx != null) {
            setReviewContext(ctx);
        }
        return ctx;
    }

    private void notifyListeners() {
        LOGGER.debug("Notifying {} listeners of ReviewContext change", listeners.size());
        listeners.forEach(listener -> listener.accept(currentReviewContext));
    }
}
