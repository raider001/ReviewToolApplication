package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.serverlessreviewtool.plugin.dataobjects.RepositoryDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for RepositoryManager covering repository CRUD, notifications, and branch updates.
 */
class RepositoryManagerTests {

    private RepositoryManager manager;

    @BeforeEach
    void setUp() {
        manager = new RepositoryManager();
    }

    @Test
    void getRepositories_initiallyEmpty() {
        assertTrue(manager.getRepositories().isEmpty());
    }

    @Test
    void getRepositoryByName_nullName_returnsNull() {
        assertNull(manager.getRepositoryByName(null));
    }

    @Test
    void getRepositoryByName_unknownName_returnsNull() {
        assertNull(manager.getRepositoryByName("nonexistent"));
    }

    @Test
    void getRepositoryByName_existingName_returnsRepository() {
        manager.setRepositoriesFromNotification(List.of(new RepositoryDescriptor("backend", "file:///backend")));

        Repository result = manager.getRepositoryByName("backend");

        assertNotNull(result);
        assertEquals("backend", result.getName());
    }

    @Test
    void setRepositoriesFromNotification_nullDescriptors_clearsRepositories() {
        manager.setRepositoriesFromNotification(List.of(new RepositoryDescriptor("backend", "file:///backend")));
        manager.setRepositoriesFromNotification(null);

        assertTrue(manager.getRepositories().isEmpty());
    }

    @Test
    void setRepositoriesFromNotification_validDescriptors_populatesRepositories() {
        manager.setRepositoriesFromNotification(List.of(
            new RepositoryDescriptor("frontend", "file:///frontend"),
            new RepositoryDescriptor("backend", "file:///backend")
        ));

        List<Repository> repos = manager.getRepositories();
        assertEquals(2, repos.size());
    }

    @Test
    void setRepositoriesFromNotification_duplicateNames_deduplicates() {
        manager.setRepositoriesFromNotification(List.of(
            new RepositoryDescriptor("backend", "file:///v1"),
            new RepositoryDescriptor("backend", "file:///v2")
        ));

        assertEquals(1, manager.getRepositories().size());
    }

    @Test
    void setRepositoriesFromNotification_preservesBranchesFromPreviousConfig() {
        manager.setRepositoriesFromNotification(List.of(new RepositoryDescriptor("backend", "file:///backend")));
        manager.getRepositories().stream()
            .filter(r -> r.getName().equals("backend"))
            .findFirst()
            .ifPresent(r -> r.setBranches(List.of("main", "develop")));

        manager.setRepositoriesFromNotification(List.of(
            new RepositoryDescriptor("backend", "file:///backend"),
            new RepositoryDescriptor("frontend", "file:///frontend")
        ));

        Repository backend = manager.getRepositoryByName("backend");
        assertNotNull(backend);
    }

    @Test
    void setRepositoriesFromNotification_blankNameDescriptor_isIgnored() {
        manager.setRepositoriesFromNotification(List.of(
            new RepositoryDescriptor("  ", "file:///blank"),
            new RepositoryDescriptor("real-repo", "file:///real")
        ));

        assertEquals(1, manager.getRepositories().size());
        assertEquals("real-repo", manager.getRepositories().getFirst().getName());
    }

    @Test
    void setRepositoriesFromNotification_repositoriesSortedByName() {
        manager.setRepositoriesFromNotification(List.of(
            new RepositoryDescriptor("zoo-repo", "file:///zoo"),
            new RepositoryDescriptor("alpha-repo", "file:///alpha"),
            new RepositoryDescriptor("middle-repo", "file:///middle")
        ));

        List<Repository> repos = manager.getRepositories();
        assertEquals("alpha-repo", repos.get(0).getName());
        assertEquals("middle-repo", repos.get(1).getName());
        assertEquals("zoo-repo", repos.get(2).getName());
    }

    @Test
    void updateBranchesForRepositories_matchingRepo_updatesBranches() {
        manager.setRepositoriesFromNotification(List.of(new RepositoryDescriptor("backend", "file:///backend")));

        manager.updateBranchesForRepositories(Map.of("backend", List.of("main", "feature/test")));

        Repository repo = manager.getRepositoryByName("backend");
        assertNotNull(repo);
        assertTrue(repo.getBranches().contains("main"));
        assertTrue(repo.getBranches().contains("feature/test"));
    }

    @Test
    void updateBranchesForRepositories_noMatchingRepo_doesNotNotify() {
        manager.setRepositoriesFromNotification(List.of(new RepositoryDescriptor("backend", "file:///backend")));

        List<List<Repository>> notifications = new CopyOnWriteArrayList<>();
        manager.addListener(notifications::add);
        notifications.clear();

        manager.updateBranchesForRepositories(Map.of("nonexistent", List.of("main")));

        assertTrue(notifications.isEmpty());
    }

    @Test
    void updateBranchesForRepositories_matchingRepo_notifiesListeners() {
        manager.setRepositoriesFromNotification(List.of(new RepositoryDescriptor("backend", "file:///backend")));

        List<List<Repository>> notifications = new CopyOnWriteArrayList<>();
        manager.addListener(notifications::add);
        notifications.clear();

        manager.updateBranchesForRepositories(Map.of("backend", List.of("main")));

        assertEquals(1, notifications.size());
    }

    @Test
    void addListener_calledImmediatelyWithCurrentRepositories() {
        manager.setRepositoriesFromNotification(List.of(new RepositoryDescriptor("myrepo", "file:///repo")));

        List<Repository> received = new ArrayList<>();
        manager.addListener(received::addAll);

        assertEquals(1, received.size());
        assertEquals("myrepo", received.getFirst().getName());
    }

    @Test
    void notifyListeners_allListenersReceiveUpdate() {
        List<List<Repository>> notificationsA = new CopyOnWriteArrayList<>();
        List<List<Repository>> notificationsB = new CopyOnWriteArrayList<>();

        manager.addListener(notificationsA::add);
        manager.addListener(notificationsB::add);
        notificationsA.clear();
        notificationsB.clear();

        manager.notifyListeners();

        assertEquals(1, notificationsA.size());
        assertEquals(1, notificationsB.size());
    }
}

