package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.configuration.AppSettings;
import com.kalynx.serverlessreviewtool.git.Git;
import com.kalynx.swingtheme.theme.LoadingStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * RepositorySyncManager handles synchronizing repositories with their remotes.
 * Performs git fetch and retrieves all review notes for configured repositories.
 * Uses LoadingStateManager to track sync operations.
 */
public class RepositorySyncManager {
    private final Git git;
    private final List<AppSettings.RepositoryConfig> repositories;

    public RepositorySyncManager(Git git, List<AppSettings.RepositoryConfig> repositories) {
        this.git = git;
        this.repositories = repositories;
    }

    /**
     * Synchronizes all configured repositories.
     * For each repository:
     * - Performs git fetch to retrieve remote changes and notes
     *
     * @return future that completes when all repositories are synced
     */
    public CompletableFuture<SyncResult> syncAllRepositories() {
        return syncAllRepositories(null, null);
    }

    /**
     * Synchronizes all configured repositories with progress callback.
     *
     * @param progressCallback callback invoked with progress messages
     * @return future that completes when all repositories are synced
     */
    public CompletableFuture<SyncResult> syncAllRepositories(Consumer<String> progressCallback) {
        return syncAllRepositories(progressCallback, null);
    }

    /**
     * Synchronizes all configured repositories with progress callback and loading state tracking.
     *
     * @param progressCallback callback invoked with progress messages
     * @param operationId unique identifier for tracking the operation in LoadingStateManager
     * @return future that completes when all repositories are synced
     */
    public CompletableFuture<SyncResult> syncAllRepositories(Consumer<String> progressCallback, String operationId) {
        if (operationId != null) {
            LoadingStateManager.getInstance().startLoading(operationId);
        }

        if (repositories == null || repositories.isEmpty()) {
            if (operationId != null) {
                LoadingStateManager.getInstance().stopLoading(operationId);
            }
            return CompletableFuture.completedFuture(
                new SyncResult(true, "No repositories configured", new ArrayList<>())
            );
        }

        List<CompletableFuture<RepositorySyncStatus>> syncFutures = new ArrayList<>();

        for (AppSettings.RepositoryConfig config : repositories) {
            CompletableFuture<RepositorySyncStatus> syncFuture = syncRepository(config, progressCallback);
            syncFutures.add(syncFuture);
        }

        return CompletableFuture.allOf(syncFutures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> {
                List<RepositorySyncStatus> results = syncFutures.stream()
                    .map(CompletableFuture::join)
                    .toList();

                boolean allSuccessful = results.stream().allMatch(r -> r.success);
                String message = allSuccessful
                    ? "All repositories synced successfully"
                    : "Some repositories failed to sync";

                if (operationId != null) {
                    LoadingStateManager.getInstance().stopLoading(operationId);
                }

                return new SyncResult(allSuccessful, message, results);
            })
            .exceptionally(ex -> {
                if (operationId != null) {
                    LoadingStateManager.getInstance().stopLoading(operationId);
                }
                return new SyncResult(
                    false,
                    "Sync failed: " + ex.getMessage(),
                    new ArrayList<>()
                );
            });
    }

    /**
     * Synchronizes a single repository.
     *
     * @param config repository configuration
     * @param progressCallback optional progress callback
     * @return future containing sync status
     */
    public CompletableFuture<RepositorySyncStatus> syncRepository(
            AppSettings.RepositoryConfig config,
            Consumer<String> progressCallback) {

        String repoName = config.getName();

        if (progressCallback != null) {
            progressCallback.accept("Syncing " + repoName + "...");
        }

        return git.fetch(repoName)
            .thenApply(ignored -> {
                if (progressCallback != null) {
                    progressCallback.accept("Fetched " + repoName);
                }
                return new RepositorySyncStatus(repoName, true, "Synced successfully");
            })
            .exceptionally(error -> {
                String errorMessage = "Failed to sync " + repoName + ": " + error.getMessage();
                if (progressCallback != null) {
                    progressCallback.accept(errorMessage);
                }
                return new RepositorySyncStatus(repoName, false, errorMessage);
            });
    }

    /**
     * Result of syncing all repositories.
     */
    public static class SyncResult {
        public final boolean success;
        public final String message;
        public final List<RepositorySyncStatus> repositoryResults;

        public SyncResult(boolean success, String message, List<RepositorySyncStatus> repositoryResults) {
            this.success = success;
            this.message = message;
            this.repositoryResults = repositoryResults;
        }
    }

    /**
     * Status of syncing a single repository.
     */
    public static class RepositorySyncStatus {
        public final String repositoryName;
        public final boolean success;
        public final String message;

        public RepositorySyncStatus(String repositoryName, boolean success, String message) {
            this.repositoryName = repositoryName;
            this.success = success;
            this.message = message;
        }
    }
}

