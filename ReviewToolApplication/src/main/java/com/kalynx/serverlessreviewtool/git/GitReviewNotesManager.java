package com.kalynx.serverlessreviewtool.git;

import com.kalynx.serverlessreviewtool.models.review.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public class GitReviewNotesManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitReviewNotesManager.class);
    private final Git git;
    private final String repositoryName;
    private static final String NOTES_REF_PREFIX = "refs/notes/reviews/";
    private static final String REMOTE = "origin";
    private static final String ZERO_OID = "0000000000000000000000000000000000000000";

    public GitReviewNotesManager(Git git, String repositoryName) {
        this.git = git;
        this.repositoryName = repositoryName;
    }

    public CompletableFuture<Void> writeReviewTitle(String reviewId, String editor, String title) {
        return writeToStream(reviewId, "metadata/title",
            () -> ReviewStreamHelper.writeTitle(getStreamPath(reviewId, "metadata/title"), editor, title));
    }

    public CompletableFuture<Void> writeReviewDescription(String reviewId, String editor, String description) {
        return writeToStream(reviewId, "metadata/description",
            () -> ReviewStreamHelper.writeDescription(getStreamPath(reviewId, "metadata/description"), editor, description));
    }

    public CompletableFuture<Void> writeReviewAuthor(String reviewId, String editor, String author) {
        return writeToStream(reviewId, "metadata/author",
            () -> ReviewStreamHelper.writeAuthor(getStreamPath(reviewId, "metadata/author"), editor, author));
    }

    public CompletableFuture<Void> writeReviewStatus(String reviewId, String editor, String status) {
        return writeToStream(reviewId, "metadata/status",
            () -> ReviewStreamHelper.writeStatus(getStreamPath(reviewId, "metadata/status"), editor, status));
    }

    public CompletableFuture<Void> writeReviewCommits(String reviewId, String editor, List<String> commits) {
        return writeToStream(reviewId, "metadata/commits",
            () -> ReviewStreamHelper.writeCommits(getStreamPath(reviewId, "metadata/commits"), editor, commits));
    }

    public CompletableFuture<Void> writeReviewer(String reviewId, String editor, ReviewerData reviewerData) {
        return writeToStream(reviewId, "reviewers",
            () -> ReviewStreamHelper.writeReviewer(getStreamPath(reviewId, "reviewers"), editor, reviewerData));
    }

    public CompletableFuture<Void> writeCommentMetadata(String reviewId, String commentId, String editor,
                                                          String file, int line, int lineEnd, String commit) {
        String streamPath = "comments/" + commentId + "/metadata";
        return writeToStream(reviewId, streamPath, () -> {
            CommentMetadata metadata = new CommentMetadata(file, line, lineEnd, commit);
            ReviewStreamHelper.writeCommentMetadata(getStreamPath(reviewId, streamPath), editor, metadata);
        });
    }

    public CompletableFuture<Void> writeCommentText(String reviewId, String commentId, String editor,
                                                      String text, String replyTo, String type) {
        String streamPath = "comments/" + commentId + "/text";
        return writeToStream(reviewId, streamPath, () -> {
            CommentTextData textData = new CommentTextData(text, replyTo, type);
            ReviewStreamHelper.writeCommentText(getStreamPath(reviewId, streamPath), editor, textData);
        });
    }

    public CompletableFuture<Void> writeCommentStatus(String reviewId, String commentId, String editor,
                                                        Boolean needsResolution, Boolean resolved) {
        String streamPath = "comments/" + commentId + "/status";
        return writeToStream(reviewId, streamPath, () -> {
            CommentStatusData statusData = new CommentStatusData(needsResolution, resolved);
            ReviewStreamHelper.writeCommentStatus(getStreamPath(reviewId, streamPath), editor, statusData);
        });
    }

    public record CommentMetadata(String file, int line, int lineEnd, String commit) {}
    public record CommentTextData(String text, String replyTo, String type) {}
    public record CommentStatusData(Boolean needsResolution, Boolean resolved) {}

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

        List<String> streamPaths = List.of(
            "metadata/title",
            "metadata/author",
            "metadata/description",
            "metadata/status",
            "metadata/commits",
            "metadata/primaryRepository",
            "metadata/branch",
            "metadata/baseBranch",
            "reviewers"
        );

        return fetchAllNotes(reviewId, streamPaths)
            .thenCompose(ignored -> getRepositoryRootCommit())
            .thenCompose(anchorCommit -> {
                try {
                    ReviewStreamHelper.writeTitle(getStreamPath(reviewId, "metadata/title"), editor, title);
                    ReviewStreamHelper.writeAuthor(getStreamPath(reviewId, "metadata/author"), editor, author);
                    ReviewStreamHelper.writeDescription(getStreamPath(reviewId, "metadata/description"), editor, description);
                    ReviewStreamHelper.writeStatus(getStreamPath(reviewId, "metadata/status"), editor, status);
                    ReviewStreamHelper.writeCommits(getStreamPath(reviewId, "metadata/commits"), editor, safeCommits);
                    ReviewStreamHelper.writePrimaryRepository(getStreamPath(reviewId, "metadata/primaryRepository"), editor, "true");
                    ReviewStreamHelper.writeBranch(getStreamPath(reviewId, "metadata/branch"), editor, branch);
                    ReviewStreamHelper.writeBaseBranch(getStreamPath(reviewId, "metadata/baseBranch"), editor, baseBranch);

                    for (String reviewer : reviewers) {
                        ReviewerData reviewerData = new ReviewerData(
                            ReviewerData.Status.PENDING.getValue(),
                            ""
                        );
                        ReviewStreamHelper.writeReviewer(getStreamPath(reviewId, "reviewers"), reviewer, reviewerData);
                    }

                    for (String streamPath : streamPaths) {
                        resolveAndNormalize(getStreamPath(reviewId, streamPath));
                    }

                    return addAllNotesToGit(reviewId, streamPaths, anchorCommit);
                } catch (IOException e) {
                    return CompletableFuture.failedFuture(e);
                }
            })
            .thenCompose(ignored -> pushAllNotes(reviewId, streamPaths));
    }

    public CompletableFuture<Void> createReviewAcrossRepositories(String reviewId,
                                                                   String editor,
                                                                   String title,
                                                                   String author,
                                                                   String description,
                                                                   String status,
                                                                   Map<String, List<String>> commitsByRepository,
                                                                   List<String> reviewers,
                                                                   List<String> repositories,
                                                                   String branch,
                                                                   String baseBranch) {
        if (repositories == null || repositories.isEmpty()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Cannot create review without repositories")
            );
        }

        String primaryRepo = repositories.getFirst();
        List<String> secondaryRepos = repositories.subList(1, repositories.size());

        List<String> primaryCommits = commitsByRepository.getOrDefault(primaryRepo, List.of());

        GitReviewNotesManager primaryManager = new GitReviewNotesManager(git, primaryRepo);
        CompletableFuture<Void> primaryFuture = primaryManager.createReview(
            reviewId, editor, title, author, description, status, primaryCommits, reviewers, branch, baseBranch
        );

        if (secondaryRepos.isEmpty()) {
            return primaryFuture;
        }

        List<CompletableFuture<Void>> secondaryFutures = secondaryRepos.stream()
            .map(repoName -> {
                List<String> repoCommits = commitsByRepository.getOrDefault(repoName, List.of());
                return createSecondaryReviewReference(repoName, reviewId, editor, repoCommits, branch, baseBranch);
            })
            .toList();

        return CompletableFuture.allOf(
            Stream.concat(
                Stream.of(primaryFuture),
                secondaryFutures.stream()
            ).toArray(CompletableFuture[]::new)
        );
    }

    private CompletableFuture<Void> createSecondaryReviewReference(String repoName, String reviewId, String editor, List<String> commits, String branch, String baseBranch) {
        GitReviewNotesManager secondaryManager = new GitReviewNotesManager(git, repoName);

        List<String> streamPaths = new ArrayList<>();
        streamPaths.add("metadata/primaryRepository");
        streamPaths.add("metadata/branch");
        streamPaths.add("metadata/baseBranch");
        if (commits != null && !commits.isEmpty()) {
            streamPaths.add("metadata/commits");
        }

        return secondaryManager.fetchAllNotes(reviewId, streamPaths)
            .thenCompose(ignored -> secondaryManager.getRepositoryRootCommit())
            .thenCompose(anchorCommit -> {
                try {
                    Path primaryRepoPath = secondaryManager.getStreamPath(reviewId, "metadata/primaryRepository");
                    ReviewStreamHelper.writePrimaryRepository(primaryRepoPath, editor, "false");
                    secondaryManager.resolveAndNormalize(primaryRepoPath);

                    Path branchPath = secondaryManager.getStreamPath(reviewId, "metadata/branch");
                    ReviewStreamHelper.writeBranch(branchPath, editor, branch);
                    secondaryManager.resolveAndNormalize(branchPath);

                    Path baseBranchPath = secondaryManager.getStreamPath(reviewId, "metadata/baseBranch");
                    ReviewStreamHelper.writeBaseBranch(baseBranchPath, editor, baseBranch);
                    secondaryManager.resolveAndNormalize(baseBranchPath);

                    if (commits != null && !commits.isEmpty()) {
                        Path commitsPath = secondaryManager.getStreamPath(reviewId, "metadata/commits");
                        ReviewStreamHelper.writeCommits(commitsPath, editor, commits);
                        secondaryManager.resolveAndNormalize(commitsPath);
                    }

                    return secondaryManager.addAllNotesToGit(reviewId, streamPaths, anchorCommit);
                } catch (IOException e) {
                    return CompletableFuture.failedFuture(e);
                }
            })
            .thenCompose(ignored -> secondaryManager.pushAllNotes(reviewId, streamPaths));
    }

    private CompletableFuture<String> getRepositoryRootCommit() {
        return git.executeAsync(
            repositoryName,
            "rev-list", "--max-parents=0", "HEAD"
        ).thenApply(output -> {
            String[] commits = output.trim().split("\n");
            if (commits.length == 0 || commits[0].trim().isEmpty()) {
                throw new IllegalStateException("No root commit found in repository " + repositoryName);
            }
            return commits[0].trim();
        });
    }

    private CompletableFuture<Void> fetchAllNotes(String reviewId, List<String> streamPaths) {
        if (streamPaths == null || streamPaths.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        long batchStart = System.nanoTime();
        List<String> refspecs = streamPaths.stream()
            .map(streamPath -> {
                String ref = NOTES_REF_PREFIX + reviewId + "/" + streamPath;
                return ref + ":" + ref;
            })
            .toList();

        return fetchNotesBatch(refspecs)
            .thenApply(ignored -> {
                LOGGER.info("TIMING [{}] fetchAllNotesBatch ({}/{} streams): {}ms",
                    reviewId, repositoryName, streamPaths.size(), elapsedMs(batchStart));
                return (Throwable) null;
            })
            .exceptionally(this::unwrapCause)
            .thenCompose(error -> {
                if (error == null) {
                    return CompletableFuture.completedFuture(null);
                }

                String message = error.getMessage() != null ? error.getMessage().toLowerCase() : "";
                boolean shouldFallback = message.contains("couldn't find remote ref") ||
                    message.contains("not found") ||
                    message.contains("non-fast-forward") ||
                    message.contains("invalid refspec");

                if (!shouldFallback) {
                    return CompletableFuture.failedFuture(error);
                }

                long fallbackStart = System.nanoTime();
                return fetchAllNotesIndividually(reviewId, streamPaths)
                    .thenApply(_ -> {
                        LOGGER.info("TIMING [{}] fetchAllNotesFallback ({}/{} streams): {}ms",
                            reviewId, repositoryName, streamPaths.size(), elapsedMs(fallbackStart));
                        return null;
                    });
            });
    }

    private CompletableFuture<Void> fetchNotesBatch(List<String> refspecs) {
        List<String> args = new ArrayList<>();
        args.add("-c");
        args.add("notes.mergeStrategy=union");
        args.add("fetch");
        args.add(REMOTE);
        args.addAll(refspecs);

        return git.executeAsync(repositoryName, args.toArray(new String[0]))
            .thenApply(_ -> null);
    }

    private CompletableFuture<Void> fetchAllNotesIndividually(String reviewId, List<String> streamPaths) {
        List<CompletableFuture<Void>> fetchFutures = streamPaths.stream()
            .map(streamPath -> fetchAndMergeNotes(reviewId, streamPath))
            .toList();

        return CompletableFuture.allOf(fetchFutures.toArray(new CompletableFuture[0]));
    }

    private CompletableFuture<Void> addAllNotesToGit(String reviewId, List<String> streamPaths, String anchorCommit) {
        List<CompletableFuture<Void>> addFutures = streamPaths.stream()
            .map(streamPath -> addNotesToGit(reviewId, streamPath, getStreamPath(reviewId, streamPath), anchorCommit))
            .toList();

        return CompletableFuture.allOf(addFutures.toArray(new CompletableFuture[0]));
    }

    private CompletableFuture<Void> pushAllNotes(String reviewId, List<String> streamPaths) {
        List<String> refs = streamPaths.stream()
            .map(streamPath -> NOTES_REF_PREFIX + reviewId + "/" + streamPath)
            .toList();

        String[] args = new String[refs.size() + 2];
        args[0] = "push";
        args[1] = REMOTE;
        for (int i = 0; i < refs.size(); i++) {
            args[i + 2] = refs.get(i);
        }

        return git.executeAsync(repositoryName, args)
            .thenApply(ignored -> null);
    }

    private CompletableFuture<Void> pushAllNotesWithLease(List<String> refs, Map<String, String> expectedRefStates) {
        List<String> args = new ArrayList<>();
        args.add("push");
        for (String ref : refs) {
            String expected = expectedRefStates.getOrDefault(ref, ZERO_OID);
            args.add("--force-with-lease=" + ref + ":" + expected);
        }
        args.add(REMOTE);
        args.addAll(refs);

        return git.executeAsync(repositoryName, args.toArray(new String[0]))
            .thenApply(ignored -> null);
    }

    private CompletableFuture<Map<String, String>> collectExpectedRefStates(List<String> refs) {
        List<CompletableFuture<Map.Entry<String, String>>> futures = refs.stream()
            .map(ref -> resolveRefOidOrZero(ref).thenApply(oid -> Map.entry(ref, oid)))
            .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> {
                Map<String, String> expectedRefStates = new LinkedHashMap<>();
                for (CompletableFuture<Map.Entry<String, String>> future : futures) {
                    Map.Entry<String, String> entry = future.join();
                    expectedRefStates.put(entry.getKey(), entry.getValue());
                }
                return expectedRefStates;
            });
    }

    private CompletableFuture<String> resolveRefOidOrZero(String ref) {
        return git.executeAsync(repositoryName, "rev-parse", "--verify", ref)
            .thenApply(String::trim)
            .exceptionally(ignored -> ZERO_OID);
    }

    /**
     * Save all review metadata in a single parallel batch operation.
     * Fetches all affected streams in parallel, writes all content locally, then pushes all refs
     * in a single git push command. Falls back to fetch-merge-retry on push conflict.
     *
     * @param reviewId       review identifier
     * @param editor         the user performing the save
     * @param title          review title
     * @param description    review description/summary
     * @param author         review author
     * @param status         overall review status string
     * @param reviewerEntries list of reviewer name → reviewer data pairs to write (includes left entries)
     * @return future completing when all metadata is written and pushed
     */
    public CompletableFuture<Void> saveAllMetadataBatch(
            String reviewId,
            String editor,
            String title,
            String description,
            String author,
            String status,
            List<Map.Entry<String, ReviewerData>> reviewerEntries) {
        return saveAllMetadataBatchWithRetry(reviewId, editor, title, description, author, status, reviewerEntries, MAX_WRITE_RETRIES);
    }

    private CompletableFuture<Void> saveAllMetadataBatchWithRetry(
            String reviewId,
            String editor,
            String title,
            String description,
            String author,
            String status,
            List<Map.Entry<String, ReviewerData>> reviewerEntries,
            int retriesLeft) {

        List<String> streamPaths = List.of(
            "metadata/title",
            "metadata/description",
            "metadata/author",
            "metadata/status",
            "reviewers"
        );

        List<String> refs = streamPaths.stream()
            .map(streamPath -> NOTES_REF_PREFIX + reviewId + "/" + streamPath)
            .toList();

        return collectExpectedRefStates(refs)
            .thenCompose(expectedRefStates -> getRepositoryRootCommit()
            .thenCompose(anchorCommit -> {
                List<CompletableFuture<Path>> extractFutures = streamPaths.stream()
                    .map(sp -> extractNoteToFile(reviewId, sp, anchorCommit))
                    .toList();

                return CompletableFuture.allOf(extractFutures.toArray(new CompletableFuture[0]))
                    .thenCompose(ignored2 -> {
                        try {
                            Path titlePath = getStreamPath(reviewId, "metadata/title");
                            Path descriptionPath = getStreamPath(reviewId, "metadata/description");
                            Path authorPath = getStreamPath(reviewId, "metadata/author");
                            Path statusPath = getStreamPath(reviewId, "metadata/status");
                            Path reviewersPath = getStreamPath(reviewId, "reviewers");

                            if (shouldWriteStringEntry(titlePath, title, ReviewStreamHelper::readTitles)) {
                                ReviewStreamHelper.writeTitle(titlePath, editor, title);
                            }
                            if (shouldWriteStringEntry(descriptionPath, description, ReviewStreamHelper::readDescriptions)) {
                                ReviewStreamHelper.writeDescription(descriptionPath, editor, description);
                            }
                            if (shouldWriteStringEntry(authorPath, author, ReviewStreamHelper::readAuthors)) {
                                ReviewStreamHelper.writeAuthor(authorPath, editor, author);
                            }
                            if (shouldWriteStringEntry(statusPath, status, ReviewStreamHelper::readStatuses)) {
                                ReviewStreamHelper.writeStatus(statusPath, editor, status);
                            }

                            Map<String, ReviewerData> latestReviewerState = loadLatestReviewerState(reviewersPath);
                            for (Map.Entry<String, ReviewerData> entry : reviewerEntries) {
                                String reviewerName = entry.getKey();
                                ReviewerData incoming = entry.getValue();
                                ReviewerData existing = latestReviewerState.get(reviewerName);
                                if (isDifferentReviewerData(existing, incoming)) {
                                    ReviewStreamHelper.writeReviewer(reviewersPath, reviewerName, incoming);
                                    latestReviewerState.put(reviewerName, incoming);
                                }
                            }

                            for (String streamPath : streamPaths) {
                                resolveAndNormalize(getStreamPath(reviewId, streamPath));
                            }

                            return addAllNotesToGit(reviewId, streamPaths, anchorCommit);
                        } catch (IOException e) {
                            return CompletableFuture.failedFuture(e);
                        }
                    });
            })
            .thenCompose(ignored -> pushAllNotesWithLease(refs, expectedRefStates)))
            .handle((ignored, ex) -> {
                if (ex == null) {
                    return CompletableFuture.<Void>completedFuture(null);
                }
                Throwable cause = unwrapCause(ex);
                if (retriesLeft > 0 && isPushConflict(cause.getMessage())) {
                    return forceResetAllFromRemote(reviewId, streamPaths)
                        .thenCompose(ignored2 -> saveAllMetadataBatchWithRetry(
                            reviewId, editor, title, description, author, status, reviewerEntries, retriesLeft - 1));
                }
                if (cause instanceof RuntimeException re) {
                    return CompletableFuture.<Void>failedFuture(re);
                }
                return CompletableFuture.<Void>failedFuture(new RuntimeException(cause));
            })
            .thenCompose(f -> f);
    }

    private boolean shouldWriteStringEntry(Path streamPath,
                                           String incomingValue,
                                           StringEntryReader reader) throws IOException {
        String normalizedIncoming = incomingValue == null ? "" : incomingValue;
        List<StreamEntry<String>> existingEntries = reader.read(streamPath);
        if (existingEntries.isEmpty()) {
            return true;
        }
        String lastValue = existingEntries.getLast().data();
        String normalizedLast = lastValue == null ? "" : lastValue;
        return !normalizedLast.equals(normalizedIncoming);
    }

    private Map<String, ReviewerData> loadLatestReviewerState(Path reviewersPath) throws IOException {
        Map<String, ReviewerData> latestByReviewer = new HashMap<>();
        for (StreamEntry<ReviewerData> entry : ReviewStreamHelper.readReviewers(reviewersPath)) {
            latestByReviewer.put(entry.editor(), entry.data());
        }
        return latestByReviewer;
    }

    private boolean isDifferentReviewerData(ReviewerData existing, ReviewerData incoming) {
        if (existing == null) {
            return true;
        }
        String existingStatus = existing.getStatus() == null ? "" : existing.getStatus();
        String incomingStatus = incoming.getStatus() == null ? "" : incoming.getStatus();
        if (!existingStatus.equals(incomingStatus)) {
            return true;
        }
        String existingComment = existing.getSummaryComment() == null ? "" : existing.getSummaryComment();
        String incomingComment = incoming.getSummaryComment() == null ? "" : incoming.getSummaryComment();
        return !existingComment.equals(incomingComment);
    }

    @FunctionalInterface
    private interface StringEntryReader {
        List<StreamEntry<String>> read(Path path) throws IOException;
    }

    private CompletableFuture<Void> forceResetAllFromRemote(String reviewId, List<String> streamPaths) {
        List<CompletableFuture<Void>> futures = streamPaths.stream()
            .map(sp -> forceResetFromRemote(reviewId, sp))
            .toList();
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private static final int MAX_WRITE_RETRIES = 3;

    private CompletableFuture<Void> writeToStream(String reviewId, String streamPath, IOOperation writer) {
        return writeToStreamWithRetry(reviewId, streamPath, writer, MAX_WRITE_RETRIES);
    }

    private CompletableFuture<Void> writeToStreamWithRetry(String reviewId, String streamPath, IOOperation writer, int retriesLeft) {
        String ref = NOTES_REF_PREFIX + reviewId + "/" + streamPath;

        return resolveRefOidOrZero(ref)
            .thenCompose(expectedRef -> getAnchorCommit()
            .thenCompose(anchorCommit ->
                extractNoteToFile(reviewId, streamPath, anchorCommit).thenCompose(filePath -> {
                    try {
                        writer.execute();
                        resolveAndNormalize(filePath);
                        return addNotesToGit(reviewId, streamPath, filePath, anchorCommit);
                    } catch (IOException e) {
                        return CompletableFuture.failedFuture(e);
                    }
                })
            )
            .thenCompose(ignored -> pushNotesWithLease(ref, expectedRef)))
            .handle((_, ex) -> {
                if (ex == null) {
                    return CompletableFuture.<Void>completedFuture(null);
                }
                Throwable cause = unwrapCause(ex);
                if (retriesLeft > 0 && isPushConflict(cause.getMessage())) {
                    return forceResetFromRemote(reviewId, streamPath)
                        .thenCompose(ignored -> writeToStreamWithRetry(reviewId, streamPath, writer, retriesLeft - 1));
                }
                if (cause instanceof RuntimeException re) {
                    return CompletableFuture.<Void>failedFuture(re);
                }
                return CompletableFuture.<Void>failedFuture(new RuntimeException(cause));
            })
            .thenCompose(f -> f);
    }

    private CompletableFuture<Void> forceResetFromRemote(String reviewId, String streamPath) {
        String ref = NOTES_REF_PREFIX + reviewId + "/" + streamPath;
        return git.executeAsync(repositoryName, "fetch", REMOTE, "+" + ref + ":" + ref)
            .thenApply(ignored -> (Void) null)
            .exceptionally(_ -> null);
    }

    private boolean isPushConflict(String message) {
        if (message == null) return false;
        return message.contains("cannot lock ref") ||
               message.contains("[rejected]") ||
               message.contains("non-fast-forward") ||
               message.contains("stale info");
    }

    private Throwable unwrapCause(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause instanceof RuntimeException) {
            cause = cause.getCause();
        }
        return cause;
    }

    private CompletableFuture<String> getAnchorCommit() {
        return getRepositoryRootCommit();
    }

    private CompletableFuture<Void> fetchAndMergeNotes(String reviewId, String streamPath) {
        String ref = NOTES_REF_PREFIX + reviewId + "/" + streamPath;
        long start = System.nanoTime();

        return git.executeAsync(
            repositoryName,
            "-c", "notes.mergeStrategy=union",
            "fetch", REMOTE,
            ref + ":" + ref
        ).exceptionally(ex -> {
            String msg = ex.getMessage();
            if (msg != null && (
                msg.toLowerCase().contains("couldn't find remote ref") ||
                msg.toLowerCase().contains("not found") ||
                msg.toLowerCase().contains("non-fast-forward"))) {
                return "";
            }
            throw new RuntimeException("Failed to fetch notes: " + msg, ex);
        }).thenApply(ignored -> {
            LOGGER.info("TIMING [{}] fetchAndMergeNotes ({}/{}): {}ms",
                reviewId, repositoryName, streamPath, elapsedMs(start));
            return null;
        });
    }

    private void resolveAndNormalize(Path filePath) throws IOException {
        if (!Files.exists(filePath)) {
            return;
        }

        List<String> lines = Files.readAllLines(filePath);

        Map<String, String> entriesById = new LinkedHashMap<>();
        List<EntryWithTimestamp> sortedEntries = new ArrayList<>();

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            try {
                String id = extractId(line);
                String timestamp = extractTimestamp(line);

                if (!entriesById.containsKey(id)) {
                    entriesById.put(id, line);
                    sortedEntries.add(new EntryWithTimestamp(id, timestamp, line));
                }
            } catch (Exception e) {
                LOGGER.warn("Skipping malformed entry: {}", line, e);
            }
        }

        sortedEntries.sort(Comparator.comparing(e -> e.timestamp));

        try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
            for (EntryWithTimestamp entry : sortedEntries) {
                writer.write(entry.json);
                writer.newLine();
            }
        }
    }

    private String extractId(String json) {
        int idStart = json.indexOf("\"id\":\"") + 6;
        int idEnd = json.indexOf("\"", idStart);
        return json.substring(idStart, idEnd);
    }

    private String extractTimestamp(String json) {
        int tsStart = json.indexOf("\"timestamp\":\"") + 13;
        int tsEnd = json.indexOf("\"", tsStart);
        return json.substring(tsStart, tsEnd);
    }

    private CompletableFuture<Void> addNotesToGit(String reviewId, String streamPath, Path filePath, String commitHash) {
        String ref = NOTES_REF_PREFIX + reviewId + "/" + streamPath;

        return git.executeAsync(
            repositoryName,
            "notes", "--ref=" + ref, "add", "-f", "-F",
            filePath.toAbsolutePath().toString(),
            commitHash
        ).exceptionally(ex -> {
            if (ex.getMessage() != null && ex.getMessage().contains("Cannot add notes")) {
                return "";
            }
            throw new RuntimeException("Failed to add notes: " + ex.getMessage(), ex);
        }).thenApply(ignored -> null);
    }

    private CompletableFuture<Void> pushNotesWithLease(String ref, String expectedRef) {
        return git.executeAsync(
            repositoryName,
            "push", "--force-with-lease=" + ref + ":" + expectedRef, REMOTE, ref
        ).thenApply(ignored -> null);
    }

    private Path getStreamPath(String reviewId, String streamPath) {
        return Path.of(System.getProperty("java.io.tmpdir"))
            .resolve("git-reviews")
            .resolve(repositoryName)
            .resolve(reviewId)
            .resolve(streamPath + ".ndjson");
    }

    public CompletableFuture<List<StreamEntry<String>>> readTitles(String reviewId) {
        return readStream(reviewId, "metadata/title",
                ReviewStreamHelper::readTitles);
    }

    public CompletableFuture<List<StreamEntry<String>>> readDescriptions(String reviewId) {
        return readStream(reviewId, "metadata/description",
                ReviewStreamHelper::readDescriptions);
    }

    public CompletableFuture<List<StreamEntry<String>>> readAuthors(String reviewId) {
        return readStream(reviewId, "metadata/author",
                ReviewStreamHelper::readAuthors);
    }

    public CompletableFuture<List<StreamEntry<String>>> readStatuses(String reviewId) {
        return readStream(reviewId, "metadata/status",
                ReviewStreamHelper::readStatuses);
    }

    public CompletableFuture<List<StreamEntry<List<String>>>> readCommits(String reviewId) {
        return readStream(reviewId, "metadata/commits",
                ReviewStreamHelper::readCommits);
    }

    public CompletableFuture<List<StreamEntry<ReviewerData>>> readReviewers(String reviewId) {
        return readStream(reviewId, "reviewers",
                ReviewStreamHelper::readReviewers);
    }

    public CompletableFuture<List<StreamEntry<CommentMetadata>>> readCommentMetadata(String reviewId, String commentId) {
        return readStream(reviewId, "comments/" + commentId + "/metadata",
                ReviewStreamHelper::readCommentMetadata);
    }

    public CompletableFuture<List<StreamEntry<CommentTextData>>> readCommentText(String reviewId, String commentId) {
        return readStream(reviewId, "comments/" + commentId + "/text",
                ReviewStreamHelper::readCommentText);
    }

    public CompletableFuture<List<StreamEntry<CommentStatusData>>> readCommentStatus(String reviewId, String commentId) {
        return readStream(reviewId, "comments/" + commentId + "/status",
                ReviewStreamHelper::readCommentStatus);
    }

    public CompletableFuture<List<String>> listCommentIds(String reviewId) {
        String refPattern = NOTES_REF_PREFIX + reviewId + "/comments/";
        return git.executeAsync(repositoryName, "for-each-ref", "--format=%(refname)", refPattern)
            .thenApply(output -> {
                if (output == null || output.trim().isEmpty()) {
                    return List.<String>of();
                }
                return java.util.Arrays.stream(output.split("\n"))
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .filter(line -> line.contains("/comments/"))
                    .map(line -> {
                        int startIdx = line.indexOf("/comments/") + 10;
                        int endIdx = line.indexOf("/", startIdx);
                        return endIdx > startIdx ? line.substring(startIdx, endIdx) : null;
                    })
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            })
            .exceptionally(_ ->  List.of());
    }

    /**
     * Read all review metadata in a single batch operation for improved performance.
     * This method fetches all required notes refs at once and reads them in parallel,
     * significantly reducing the number of Git operations compared to individual reads.
     *
     * @param reviewId the review identifier
     * @return future containing all metadata (title, description, author, status, reviewers)
     */
    public CompletableFuture<ReviewMetadata> readAllMetadata(String reviewId) {
        return readAllMetadataInternal(reviewId, true);
    }

    /**
     * Read all review metadata from local notes refs without fetching remote note refs.
     *
     * @param reviewId the review identifier
     * @return future containing all locally available metadata
     */
    public CompletableFuture<ReviewMetadata> readAllMetadataFromLocal(String reviewId) {
        return readAllMetadataInternal(reviewId, false);
    }

    private CompletableFuture<ReviewMetadata> readAllMetadataInternal(String reviewId, boolean fetchRemoteNotes) {
        List<String> streamPaths = List.of(
            "metadata/title",
            "metadata/description",
            "metadata/author",
            "metadata/primaryRepository",
            "metadata/branch",
            "metadata/baseBranch",
            "metadata/status",
            "reviewers"
        );

        CompletableFuture<Void> fetchFuture;
        if (fetchRemoteNotes) {
            long fetchAllStart = System.nanoTime();
            fetchFuture = fetchAllNotes(reviewId, streamPaths)
                .thenRun(() -> LOGGER.info("TIMING [{}] readAllMetadata fetchAllNotes ({} streams, parallel, repo={}): {}ms",
                    reviewId, streamPaths.size(), repositoryName, elapsedMs(fetchAllStart)));
        } else {
            fetchFuture = CompletableFuture.completedFuture(null);
        }

        return fetchFuture
            .thenCompose(ignored -> getAnchorCommit())
            .thenCompose(anchorCommit -> {
                long extractStart = System.nanoTime();
                List<CompletableFuture<Path>> extractFutures = streamPaths.stream()
                    .map(streamPath -> extractNoteToFile(reviewId, streamPath, anchorCommit))
                    .toList();

                return CompletableFuture.allOf(extractFutures.toArray(new CompletableFuture[0]))
                    .thenApply(ignored2 -> {
                        LOGGER.info("TIMING [{}] readAllMetadata extractNoteToFile ({} streams, parallel, repo={}): {}ms",
                            reviewId, streamPaths.size(), repositoryName, elapsedMs(extractStart));
                        try {
                            Path titlePath = extractFutures.get(0).join();
                            Path descPath = extractFutures.get(1).join();
                            Path authorPath = extractFutures.get(2).join();
                            Path primaryRepoPath = extractFutures.get(3).join();
                            Path branchPath = extractFutures.get(4).join();
                            Path baseBranchPath = extractFutures.get(5).join();
                            Path statusPath = extractFutures.get(6).join();
                            Path reviewersPath = extractFutures.get(7).join();

                            resolveAndNormalize(titlePath);
                            resolveAndNormalize(descPath);
                            resolveAndNormalize(authorPath);
                            resolveAndNormalize(primaryRepoPath);
                            resolveAndNormalize(branchPath);
                            resolveAndNormalize(baseBranchPath);
                            resolveAndNormalize(statusPath);
                            resolveAndNormalize(reviewersPath);

                            return new ReviewMetadata(
                                ReviewStreamHelper.readTitles(titlePath),
                                ReviewStreamHelper.readDescriptions(descPath),
                                ReviewStreamHelper.readAuthors(authorPath),
                                ReviewStreamHelper.readPrimaryRepository(primaryRepoPath),
                                ReviewStreamHelper.readBranch(branchPath),
                                ReviewStreamHelper.readBaseBranch(baseBranchPath),
                                ReviewStreamHelper.readStatuses(statusPath),
                                ReviewStreamHelper.readReviewers(reviewersPath)
                            );
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read metadata: " + e.getMessage(), e);
                        }
                    });
            });
    }

    /**
     * Container for all review metadata retrieved in a single batch operation.
     */
    public record ReviewMetadata(
        List<StreamEntry<String>> titles,
        List<StreamEntry<String>> descriptions,
        List<StreamEntry<String>> authors,
        List<StreamEntry<String>> primaryRepository,
        List<StreamEntry<String>> branches,
        List<StreamEntry<String>> baseBranches,
        List<StreamEntry<String>> statuses,
        List<StreamEntry<ReviewerData>> reviewers
    ) {}

    private <T> CompletableFuture<List<StreamEntry<T>>> readStream(
            String reviewId,
            String streamPath,
            IOReader<T> reader) {

        return fetchAndMergeNotes(reviewId, streamPath)
            .thenCompose(ignored -> getAnchorCommit())
            .thenCompose(anchorCommit -> extractNoteToFile(reviewId, streamPath, anchorCommit))
            .thenApply(filePath -> {
                try {
                    resolveAndNormalize(filePath);
                    return reader.read(filePath);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read stream: " + e.getMessage(), e);
                }
            });
    }

    private CompletableFuture<Path> extractNoteToFile(String reviewId, String streamPath, String commitHash) {
        String ref = NOTES_REF_PREFIX + reviewId + "/" + streamPath;
        Path filePath = getStreamPath(reviewId, streamPath);

        return git.executeAsync(
            repositoryName,
            "notes", "--ref=" + ref, "show", commitHash
        ).thenApply(content -> {
            try {
                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, content);
                return filePath;
            } catch (IOException e) {
                throw new RuntimeException("Failed to write note content to file: " + e.getMessage(), e);
            }
        }).exceptionally(ex -> {
            if (ex.getMessage() != null && ex.getMessage().contains("no note found")) {
                try {
                    Files.createDirectories(filePath.getParent());
                    Files.writeString(filePath, "");
                    return filePath;
                } catch (IOException e) {
                    throw new RuntimeException("Failed to create empty file: " + e.getMessage(), e);
                }
            }
            throw new RuntimeException("Failed to extract note: " + ex.getMessage(), ex);
        });
    }

    @FunctionalInterface
    private interface IOOperation {
        void execute() throws IOException;
    }

    @FunctionalInterface
    private interface IOReader<T> {
        List<StreamEntry<T>> read(Path path) throws IOException;
    }

    private record EntryWithTimestamp(String id, String timestamp, String json) {}

    private static long elapsedMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }
}








