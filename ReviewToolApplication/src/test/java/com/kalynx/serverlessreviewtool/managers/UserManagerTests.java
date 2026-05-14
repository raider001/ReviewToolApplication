package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.models.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for UserManager covering user CRUD and listener notification.
 */
class UserManagerTests {

    private UserManager manager;

    @BeforeEach
    void setUp() {
        manager = new UserManager();
    }

    @Test
    void getUsers_initiallyEmpty() {
        assertTrue(manager.getUsers().isEmpty());
    }

    @Test
    void addUsers_singleUser_addedSuccessfully() {
        manager.addUsers(List.of(new User("Alice", "alice@example.com", "alice")));

        assertEquals(1, manager.getUsers().size());
        assertEquals("alice", manager.getUsers().getFirst().getUsername());
    }

    @Test
    void addUsers_duplicateUsername_notAddedTwice() {
        User user = new User("Alice", "alice@example.com", "alice");
        manager.addUsers(List.of(user));
        manager.addUsers(List.of(new User("Alice2", "alice2@example.com", "alice")));

        assertEquals(1, manager.getUsers().size());
        assertEquals("Alice", manager.getUsers().getFirst().getName());
    }

    @Test
    void addUsers_multipleUsers_allAdded() {
        manager.addUsers(List.of(
            new User("Alice", "alice@example.com", "alice"),
            new User("Bob", "bob@example.com", "bob")
        ));

        assertEquals(2, manager.getUsers().size());
    }

    @Test
    void addUsers_notifiesListeners() {
        List<List<User>> notifications = new CopyOnWriteArrayList<>();
        manager.addListener(notifications::add);
        notifications.clear();

        manager.addUsers(List.of(new User("Alice", "alice@example.com", "alice")));

        assertEquals(1, notifications.size());
    }

    @Test
    void addUsers_noDuplicatesAdded_doesNotNotify() {
        manager.addUsers(List.of(new User("Alice", "alice@example.com", "alice")));

        List<List<User>> notifications = new CopyOnWriteArrayList<>();
        manager.addListener(notifications::add);
        notifications.clear();

        manager.addUsers(List.of(new User("Alice", "alice@example.com", "alice")));

        assertTrue(notifications.isEmpty());
    }

    @Test
    void removeUsers_varargs_removesUser() {
        manager.addUsers(List.of(new User("Alice", "alice@example.com", "alice")));

        manager.removeUsers("alice");

        assertTrue(manager.getUsers().isEmpty());
    }

    @Test
    void removeUsers_varargs_notifiesListeners() {
        manager.addUsers(List.of(new User("Alice", "alice@example.com", "alice")));

        List<List<User>> notifications = new CopyOnWriteArrayList<>();
        manager.addListener(notifications::add);
        notifications.clear();

        manager.removeUsers("alice");

        assertEquals(1, notifications.size());
        assertTrue(notifications.getFirst().isEmpty());
    }

    @Test
    void removeUsers_varargs_unknownUsername_doesNotNotify() {
        List<List<User>> notifications = new CopyOnWriteArrayList<>();
        manager.addListener(notifications::add);
        notifications.clear();

        manager.removeUsers("nonexistent");

        assertTrue(notifications.isEmpty());
    }

    @Test
    void removeUsers_listOverload_removesMatchingUsers() {
        manager.addUsers(List.of(
            new User("Alice", "alice@example.com", "alice"),
            new User("Bob", "bob@example.com", "bob")
        ));

        manager.removeUsers(List.of(new User("Alice", "alice@example.com", "alice")));

        assertEquals(1, manager.getUsers().size());
        assertEquals("bob", manager.getUsers().getFirst().getUsername());
    }

    @Test
    void addListener_calledImmediatelyWithCurrentUsers() {
        manager.addUsers(List.of(new User("Alice", "alice@example.com", "alice")));

        List<User> received = new ArrayList<>();
        manager.addListener(received::addAll);

        assertFalse(received.isEmpty());
        assertEquals("alice", received.getFirst().getUsername());
    }

    @Test
    void removeListener_noLongerReceivesNotifications() {
        List<List<User>> notifications = new CopyOnWriteArrayList<>();
        Consumer<List<User>> listener = notifications::add;

        manager.addListener(listener);
        notifications.clear();
        manager.removeListener(listener);

        manager.addUsers(List.of(new User("Alice", "alice@example.com", "alice")));

        assertTrue(notifications.isEmpty());
    }
}



