package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.configuration.AppSettings;
import com.kalynx.serverlessreviewtool.git.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for RepositorySyncManager covering sync operations and progress callbacks.
 */
class RepositorySyncManagerTests {

    private Git git;

    @BeforeEach
    void setUp() {
        git = mock(Git.class);
    }

    @Test
    void syncAllRepositories_noArgs_emptyRepositories_returnsSuccessResult() throws Exception {
        RepositorySyncManager syncManager = new RepositorySyncManager(git, List.of());

        RepositorySyncManager.SyncResult result = syncManager.syncAllRepositories()
            .get(2, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.success);
        assertEquals("No repositories configured", result.message);
        assertTrue(result.repositoryResults.isEmpty());
    }

    @Test
    void syncAllRepositories_nullRepositories_returnsSuccessResult() throws Exception {
        RepositorySyncManager syncManager = new RepositorySyncManager(git, null);

        RepositorySyncManager.SyncResult result = syncManager.syncAllRepositories()
            .get(2, TimeUnit.SECONDS);

        assertTrue(result.success);
    }

    @Test
    void syncAllRepositories_successfulFetch_allSucceeded() throws Exception {
        when(git.fetch(anyString())).thenReturn(CompletableFuture.completedFuture(null));

        RepositorySyncManager syncManager = new RepositorySyncManager(git,
            List.of(new AppSettings.RepositoryConfig("repo-a", "file:///a"),
                    new AppSettings.RepositoryConfig("repo-b", "file:///b")));

        RepositorySyncManager.SyncResult result = syncManager.syncAllRepositories()
            .get(2, TimeUnit.SECONDS);

        assertTrue(result.success);
        assertEquals("All repositories synced successfully", result.message);
        assertEquals(2, result.repositoryResults.size());
        assertTrue(result.repositoryResults.stream().allMatch(r -> r.success));
    }

    @Test
    void syncAllRepositories_fetchFails_reportedAsFailure() throws Exception {
        when(git.fetch("repo-a")).thenReturn(CompletableFuture.completedFuture(null));
        when(git.fetch("repo-b")).thenReturn(CompletableFuture.failedFuture(new RuntimeException("connection refused")));

        RepositorySyncManager syncManager = new RepositorySyncManager(git,
            List.of(new AppSettings.RepositoryConfig("repo-a", "file:///a"),
                    new AppSettings.RepositoryConfig("repo-b", "file:///b")));

        RepositorySyncManager.SyncResult result = syncManager.syncAllRepositories()
            .get(2, TimeUnit.SECONDS);

        assertFalse(result.success);
        assertEquals("Some repositories failed to sync", result.message);
    }

    @Test
    void syncAllRepositories_withProgressCallback_receivesMessages() throws Exception {
        when(git.fetch(anyString())).thenReturn(CompletableFuture.completedFuture(null));

        RepositorySyncManager syncManager = new RepositorySyncManager(git,
            List.of(new AppSettings.RepositoryConfig("myrepo", "file:///myrepo")));

        List<String> messages = new ArrayList<>();
        syncManager.syncAllRepositories(messages::add).get(2, TimeUnit.SECONDS);

        assertFalse(messages.isEmpty());
        assertTrue(messages.stream().anyMatch(m -> m.contains("myrepo")));
    }

    @Test
    void syncAllRepositories_withProgressCallbackAndOperationId_completesSuccessfully() throws Exception {
        when(git.fetch(anyString())).thenReturn(CompletableFuture.completedFuture(null));

        RepositorySyncManager syncManager = new RepositorySyncManager(git,
            List.of(new AppSettings.RepositoryConfig("myrepo", "file:///myrepo")));

        RepositorySyncManager.SyncResult result = syncManager
            .syncAllRepositories(_ -> {}, "test-op-id")
            .get(2, TimeUnit.SECONDS);

        assertTrue(result.success);
    }

    @Test
    void syncRepository_successfulFetch_returnsSyncedStatus() throws Exception {
        when(git.fetch("myrepo")).thenReturn(CompletableFuture.completedFuture(null));

        RepositorySyncManager syncManager = new RepositorySyncManager(git, List.of());
        AppSettings.RepositoryConfig config = new AppSettings.RepositoryConfig("myrepo", "file:///myrepo");

        RepositorySyncManager.RepositorySyncStatus status = syncManager
            .syncRepository(config, null)
            .get(2, TimeUnit.SECONDS);

        assertTrue(status.success);
        assertEquals("myrepo", status.repositoryName);
        assertEquals("Synced successfully", status.message);
    }

    @Test
    void syncRepository_fetchFails_returnsFailedStatus() throws Exception {
        when(git.fetch("myrepo")).thenReturn(CompletableFuture.failedFuture(new RuntimeException("timeout")));

        RepositorySyncManager syncManager = new RepositorySyncManager(git, List.of());
        AppSettings.RepositoryConfig config = new AppSettings.RepositoryConfig("myrepo", "file:///myrepo");

        RepositorySyncManager.RepositorySyncStatus status = syncManager
            .syncRepository(config, null)
            .get(2, TimeUnit.SECONDS);

        assertFalse(status.success);
        assertEquals("myrepo", status.repositoryName);
        assertTrue(status.message.contains("timeout"));
    }

    @Test
    void syncRepository_withProgressCallback_receivesMessages() throws Exception {
        when(git.fetch("myrepo")).thenReturn(CompletableFuture.completedFuture(null));

        RepositorySyncManager syncManager = new RepositorySyncManager(git, List.of());
        AppSettings.RepositoryConfig config = new AppSettings.RepositoryConfig("myrepo", "file:///myrepo");

        List<String> messages = new ArrayList<>();
        syncManager.syncRepository(config, messages::add).get(2, TimeUnit.SECONDS);

        assertTrue(messages.stream().anyMatch(m -> m.contains("myrepo")));
    }

    @Test
    void syncRepository_fetchFails_progressCallbackReceivesErrorMessage() throws Exception {
        when(git.fetch("myrepo")).thenReturn(CompletableFuture.failedFuture(new RuntimeException("network error")));

        RepositorySyncManager syncManager = new RepositorySyncManager(git, List.of());
        AppSettings.RepositoryConfig config = new AppSettings.RepositoryConfig("myrepo", "file:///myrepo");

        List<String> messages = new ArrayList<>();
        syncManager.syncRepository(config, messages::add).get(2, TimeUnit.SECONDS);

        assertTrue(messages.stream().anyMatch(m -> m.contains("Failed")));
    }
}

