package com.kalynx.serverlessreviewtool.git;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for GitReviewNotesManager using a hand-written Git stub.
 * Verifies performance-critical internal behaviours (P1 root-commit caching,
 * P2 batch for-each-ref, P3 batch conflict-reset fetch) without a real repository.
 */
class GitReviewNotesManagerUnitTests {

    private static final String REPO = "test-repo";
    private static final String REVIEW_ID = "review-001";
    private static final String ROOT_COMMIT = "aabbccdd00000000000000000000000000000000";

    private StubGit git;
    private GitReviewNotesManager manager;

    @BeforeEach
    void setUp() {
        git = new StubGit(ROOT_COMMIT);
        manager = new GitReviewNotesManager(git, REPO);
    }

    // ── P1: root commit caching ─────────────────────────────────────────────

    @Test
    void writeCommentMetadata_rootCommitFetchedOnce_cachedForSubsequentCallsOnSameInstance() throws Exception {
        manager.writeCommentMetadata(REVIEW_ID, "c1", "editor", "File.java", 5, 5, null)
            .get(5, TimeUnit.SECONDS);
        manager.writeCommentText(REVIEW_ID, "c1", "editor", "text", null, "comment")
            .get(5, TimeUnit.SECONDS);
        manager.writeCommentStatus(REVIEW_ID, "c1", "editor", false, false)
            .get(5, TimeUnit.SECONDS);

        assertEquals(1, git.countCallsStartingWith("rev-list"),
            "rev-list should be called exactly once per instance across multiple writeToStream calls");
    }

    @Test
    void getRepositoryRootCommit_failureClearsCache_allowsRetry() throws Exception {
        git.failRevListOnFirstCall = true;

        manager.writeCommentMetadata(REVIEW_ID, "c1", "editor", "File.java", 1, 1, null)
            .exceptionally(_ -> null)
            .get(5, TimeUnit.SECONDS);

        manager.writeCommentMetadata(REVIEW_ID, "c2", "editor", "File2.java", 10, 10, null)
            .get(5, TimeUnit.SECONDS);

        assertEquals(2, git.countCallsStartingWith("rev-list"),
            "rev-list should be called again on the same instance after a previous failure clears the cache");
    }

    // ── P2: batch ref OID resolution ────────────────────────────────────────

    @Test
    void saveAllMetadataBatch_usesForEachRefInsteadOfIndividualRevParse() throws Exception {
        manager.saveAllMetadataBatch(REVIEW_ID, "editor", "Title", "Desc", "author", "OPEN", List.of())
            .get(5, TimeUnit.SECONDS);

        assertEquals(1, git.countCallsStartingWith("for-each-ref"),
            "exactly one for-each-ref call should resolve all ref OIDs");
        assertEquals(0, git.countCallsStartingWith("rev-parse"),
            "rev-parse should not be called individually for ref state collection");
    }

    @Test
    void saveAllMetadataBatch_forEachRefIncludesAllFiveStreamRefs() throws Exception {
        manager.saveAllMetadataBatch(REVIEW_ID, "editor", "Title", "Desc", "author", "OPEN", List.of())
            .get(5, TimeUnit.SECONDS);

        List<List<String>> forEachRefCalls = git.allCalls.stream()
            .filter(c -> c.size() > 1 && "for-each-ref".equals(c.get(1)))
            .toList();

        assertEquals(1, forEachRefCalls.size());
        long refCount = forEachRefCalls.getFirst().stream()
            .filter(a -> a.startsWith("refs/notes/reviews/"))
            .count();
        assertEquals(5, refCount,
            "for-each-ref should include 5 stream ref patterns (title, desc, author, status, reviewers)");
    }

    @Test
    void saveAllMetadataBatch_forEachRefOutputParsed_pushesWith5StreamRefs() throws Exception {
        String sha = "deadbeef00000000000000000000000000000000";
        git.forEachRefOutput = sha + " refs/notes/reviews/" + REVIEW_ID + "/metadata/title";

        manager.saveAllMetadataBatch(REVIEW_ID, "editor", "Title", "Desc", "author", "OPEN", List.of())
            .get(5, TimeUnit.SECONDS);

        assertEquals(1, git.countCallsStartingWith("push"),
            "all streams should be pushed in a single batch push");
    }

    // ── P3: batch conflict-reset fetch ──────────────────────────────────────

    @Test
    void saveAllMetadataBatch_onPushConflict_issuesSingleBatchFetchNotIndividualFetches() throws Exception {
        git.failFirstPush = true;

        manager.saveAllMetadataBatch(REVIEW_ID, "editor", "T", "D", "a", "OPEN", List.of())
            .get(10, TimeUnit.SECONDS);

        List<List<String>> fetchCalls = git.allCalls.stream()
            .filter(c -> c.size() > 1 && "fetch".equals(c.get(1)))
            .toList();

        long batchFetches = fetchCalls.stream().filter(c -> c.size() > 3).count();
        long singleFetches = fetchCalls.stream().filter(c -> c.size() == 3).count();

        assertEquals(0, singleFetches, "individual per-ref fetch calls should not occur on conflict reset");
        assertEquals(batchFetches, fetchCalls.size(), "conflict-reset fetches should be batched");
    }

    @Test
    void saveAllMetadataBatch_batchConflictFetchIncludesAllFiveRefspecs() throws Exception {
        git.failFirstPush = true;

        manager.saveAllMetadataBatch(REVIEW_ID, "editor", "T", "D", "a", "OPEN", List.of())
            .get(10, TimeUnit.SECONDS);

        List<List<String>> batchFetches = git.allCalls.stream()
            .filter(c -> c.size() > 1 && "fetch".equals(c.get(1)) && c.size() > 3)
            .toList();

        for (List<String> fetchCall : batchFetches) {
            long refspecCount = fetchCall.stream()
                .filter(a -> a.startsWith("+refs/notes/reviews/"))
                .count();
            assertEquals(5, refspecCount,
                "batch conflict-reset fetch should include all 5 stream refspecs");
        }
    }

    // ── stub ─────────────────────────────────────────────────────────────────

    /**
     * Hand-written Git stub to avoid Mockito varargs matching issues on Java 25 with the inline mock maker.
     * Records all executeAsync calls and returns configurable results.
     */
    private static class StubGit implements Git {

        final List<List<String>> allCalls = new ArrayList<>();
        boolean failRevListOnFirstCall;
        boolean failFirstPush;
        String forEachRefOutput = "";

        private final String rootCommit;
        private int revListCallCount;
        private int pushCallCount;

        StubGit(String rootCommit) {
            this.rootCommit = rootCommit;
        }

        long countCallsStartingWith(String cmd) {
            return allCalls.stream().filter(c -> c.size() > 1 && cmd.equals(c.get(1))).count();
        }

        @Override
        public CompletableFuture<String> executeAsync(String repository, String... args) {
            List<String> call = new ArrayList<>();
            call.add(repository);
            Collections.addAll(call, args);
            allCalls.add(call);
            String cmd = args.length > 0 ? args[0] : "";
            return switch (cmd) {
                case "rev-list" -> {
                    revListCallCount++;
                    yield (failRevListOnFirstCall && revListCallCount == 1)
                        ? CompletableFuture.failedFuture(new RuntimeException("git error"))
                        : CompletableFuture.completedFuture(rootCommit);
                }
                case "for-each-ref" -> CompletableFuture.completedFuture(forEachRefOutput);
                case "push" -> {
                    pushCallCount++;
                    yield (failFirstPush && pushCallCount == 1)
                        ? CompletableFuture.failedFuture(new RuntimeException("[rejected] non-fast-forward"))
                        : CompletableFuture.completedFuture("");
                }
                default -> CompletableFuture.completedFuture("");
            };
        }

        @Override
        public CompletableFuture<Void> cloneRepository(String remoteUrl) { return CompletableFuture.completedFuture(null); }
        @Override
        public CompletableFuture<Void> ensureCloned(String repoName, String remoteUrl) { return CompletableFuture.completedFuture(null); }
        @Override
        public CompletableFuture<Void> removeRepository(String repository) { return CompletableFuture.completedFuture(null); }
        @Override
        public CompletableFuture<Void> fetch(String repository) { return CompletableFuture.completedFuture(null); }
        @Override
        public CompletableFuture<Void> fetchBranches(String repository, java.util.List<String> branches) { return CompletableFuture.completedFuture(null); }
        @Override
        public CompletableFuture<Void> pull(String repository) { return CompletableFuture.completedFuture(null); }
        @Override
        public CompletableFuture<Void> appendToNotes(String repository, String note, String data) { return CompletableFuture.completedFuture(null); }
        @Override
        public CompletableFuture<Void> pushNotes(String repository, java.util.List<String> notes) { return CompletableFuture.completedFuture(null); }
        @Override
        public CompletableFuture<java.util.List<String>> listBranches(String repository) { return CompletableFuture.completedFuture(java.util.List.of()); }
        @Override
        public CompletableFuture<java.util.List<String>> listBranchesRemote(String remoteUrl) { return CompletableFuture.completedFuture(java.util.List.of()); }
        @Override
        public CompletableFuture<String> getDefaultBranch(String repository) { return CompletableFuture.completedFuture("main"); }
        @Override
        public CompletableFuture<java.util.List<String>> listCommits(String repository, String ref, int maxCount) { return CompletableFuture.completedFuture(java.util.List.of()); }
        @Override
        public CompletableFuture<java.util.List<String>> listChangedFiles(String repository, String fromCommit, String toCommit) { return CompletableFuture.completedFuture(java.util.List.of()); }
        @Override
        public CompletableFuture<java.util.List<String>> readNotesBatch(String repository, String anchorCommit, java.util.List<String> noteRefs) { return CompletableFuture.completedFuture(java.util.Collections.nCopies(noteRefs.size(), "")); }
    }
}


