package com.kalynx.serverlessreviewtool.git;

import com.kalynx.serverlessreviewtool.managers.RepositoryManager;
import com.kalynx.serverlessreviewtool.models.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages per-repository blobless {@code --no-checkout} git clones for diff operations.
 *
 * <p>Each repository gets a {@code git clone --filter=blob:none --no-checkout} clone under
 * {@code {java.io.tmpdir}/srt-review-clones/{sanitized-repoName}/}.  Blobs are fetched lazily
 * by git when a {@code git show sha:path} command runs against the clone.  All remote-tracking
 * refs (e.g. {@code origin/main}) are available immediately after the initial clone.
 *
 * <p>Clones are keyed by repository name and shared across concurrent callers.
 * The temp directory is deleted on JVM shutdown.
 */
public class ReviewCloneManagerImpl implements ReviewCloneManager {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewCloneManagerImpl.class);
    private static final Duration CLONE_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration CMD_TIMEOUT = Duration.ofSeconds(60);
    private static final long REFRESH_TTL_MS = 30_000L;

    private final RepositoryManager repositoryManager;
    private final Path tempBase;

    /** Futures keyed by repoName — multiple callers share the same in-flight clone future. */
    private final ConcurrentHashMap<String, CompletableFuture<Path>> cloneFutures =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastRefreshTimeMs = new ConcurrentHashMap<>();

    public ReviewCloneManagerImpl(RepositoryManager repositoryManager) {
        this.repositoryManager = repositoryManager;
        this.tempBase = Path.of(System.getProperty("java.io.tmpdir"), "srt-review-clones");
        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(this::deleteTempBase));
    }

    // -------------------------------------------------------------------------
    // ReviewCloneManager implementation
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> ensureClone(String repoName) {
        return getOrCreateClone(repoName).thenApply(_ -> null);
    }

    @Override
    public CompletableFuture<String> execute(String repoName, String... args) {
        return getOrCreateClone(repoName)
                .thenCompose(clonePath -> runGit(clonePath, CMD_TIMEOUT, args));
    }

    @Override
    public CompletableFuture<String> getDefaultBranch(String repoName) {
        return execute(repoName, "symbolic-ref", "refs/remotes/origin/HEAD")
                .thenApply(output -> {
                    String ref = output.trim();
                    return ref.startsWith("refs/remotes/origin/")
                            ? ref.substring("refs/remotes/origin/".length()) : ref;
                })
                .exceptionallyCompose(_ ->
                        execute(repoName, "rev-parse", "--abbrev-ref", "origin/HEAD")
                                .thenApply(output -> {
                                    String ref = output.trim();
                                    return ref.startsWith("origin/")
                                            ? ref.substring("origin/".length()) : ref;
                                })
                                .exceptionally(_ -> "main"));
    }

    @Override
    public CompletableFuture<List<String>> listCommits(String repoName, String ref, int maxCount) {
        return getOrCreateClone(repoName)
                .thenCompose(clonePath -> resolveBranchRef(clonePath, ref)
                        .thenCompose(resolvedRef -> runGit(clonePath, CMD_TIMEOUT,
                                "log", resolvedRef, "--format=%H|%an|%ai|%s",
                                "-n", String.valueOf(maxCount))))
                .thenApply(output -> Arrays.stream(output.split("\n"))
                        .filter(line -> !line.trim().isEmpty())
                        .collect(Collectors.toList()));
    }

    @Override
    public CompletableFuture<List<String>> listChangedFiles(String repoName,
                                                             String fromCommit,
                                                             String toCommit) {
        if (fromCommit == null || fromCommit.isBlank() || toCommit == null || toCommit.isBlank()) {
            LOG.warn("Cannot list changed files for '{}': from='{}', to='{}'",
                    repoName, fromCommit, toCommit);
            return CompletableFuture.completedFuture(List.of());
        }
        return execute(repoName, "diff", "--name-status", fromCommit + "..." + toCommit)
                .thenApply(output -> Arrays.stream(output.split("\n"))
                        .filter(line -> !line.trim().isEmpty())
                        .collect(Collectors.toList()));
    }

    @Override
    public CompletableFuture<Void> refresh(String repoName) {
        long now = System.currentTimeMillis();
        Long last = lastRefreshTimeMs.get(repoName);
        if (last != null && now - last < REFRESH_TTL_MS) {
            LOG.debug("Skipping refresh for '{}' (within {}ms TTL)", repoName, REFRESH_TTL_MS);
            return CompletableFuture.completedFuture(null);
        }
        return getOrCreateClone(repoName)
                .thenCompose(clonePath ->
                        runGit(clonePath, CLONE_TIMEOUT, "fetch", "--filter=blob:none", "origin"))
                .thenApply(_ -> {
                    lastRefreshTimeMs.put(repoName, System.currentTimeMillis());
                    return null;
                });
    }

    // -------------------------------------------------------------------------
    // Clone lifecycle
    // -------------------------------------------------------------------------

    private CompletableFuture<Path> getOrCreateClone(String repoName) {
        // Fast path: already have a non-failed future.
        CompletableFuture<Path> existing = cloneFutures.get(repoName);
        if (existing != null && !existing.isCompletedExceptionally()) {
            return existing;
        }
        // compute() is atomic — prevents duplicate clone attempts under concurrent load.
        return cloneFutures.compute(repoName, (name, current) -> {
            if (current != null && !current.isCompletedExceptionally()) {
                return current;
            }
            return initiateClone(name);
        });
    }

    private CompletableFuture<Path> initiateClone(String repoName) {
        Repository repo = repositoryManager.getRepositoryByName(repoName);
        if (repo == null || repo.getUrl() == null || repo.getUrl().isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No URL registered for repository: " + repoName));
        }
        String remoteUrl = repo.getUrl();
        Path clonePath = tempBase.resolve(sanitize(repoName));

        // A valid git repo always has .git/HEAD. If .git exists but HEAD is missing the
        // previous clone failed mid-way — clean it up so we can re-clone cleanly.
        if (Files.isRegularFile(clonePath.resolve(".git").resolve("HEAD"))) {
            LOG.debug("Re-using existing blobless clone for '{}' at {}", repoName, clonePath);
            return CompletableFuture.completedFuture(clonePath);
        }
        if (Files.exists(clonePath)) {
            LOG.warn("Removing incomplete/corrupt clone directory for '{}' at {}", repoName, clonePath);
            deleteDirectory(clonePath);
        }

        try {
            Files.createDirectories(tempBase);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }

        LOG.info("Cloning '{}' (blobless) from {} into {}", repoName, remoteUrl, clonePath);
        return runGit(tempBase, CLONE_TIMEOUT,
                "clone", "--filter=blob:none", "--no-checkout", remoteUrl, sanitize(repoName))
                .thenApply(_ -> {
                    LOG.info("Blobless clone of '{}' complete", repoName);
                    return clonePath;
                });
    }

    // -------------------------------------------------------------------------
    // Ref resolution (mirrors GitImpl pattern for --no-checkout clones)
    // -------------------------------------------------------------------------

    private CompletableFuture<String> resolveBranchRef(Path clonePath, String ref) {
        if (ref == null || ref.trim().isEmpty()) return CompletableFuture.completedFuture(ref);
        String[] parts = ref.split("\\.\\.");
        if (parts.length == 2) {
            return resolveSingle(clonePath, parts[0])
                    .thenCombine(resolveSingle(clonePath, parts[1]),
                            (from, to) -> from + ".." + to);
        }
        return resolveSingle(clonePath, ref);
    }

    private CompletableFuture<String> resolveSingle(Path clonePath, String branch) {
        String b = branch.trim();
        if (b.isEmpty() || b.startsWith("refs/") || b.startsWith("origin/") || b.contains("[")) {
            return CompletableFuture.completedFuture(b);
        }
        // Try bare name first; fall back to origin/ prefix for --no-checkout clones.
        return runGit(clonePath, CMD_TIMEOUT, "rev-parse", "--verify", b)
                .thenApply(_ -> b)
                .exceptionally(_ -> "origin/" + b);
    }

    // -------------------------------------------------------------------------
    // Internal git runner
    // -------------------------------------------------------------------------

    private CompletableFuture<String> runGit(Path workDir, Duration timeout, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);

        CompletableFuture<String> future = new CompletableFuture<>();
        ProcessUtils.runProcess(command)
                .workingDirectory(workDir)
                .timeout(timeout)
                .onSuccess(future::complete)
                .onFailure(err -> future.completeExceptionally(
                        new RuntimeException("git " + String.join(" ", args) + " failed: " + err)))
                .onTimeout(() -> future.completeExceptionally(
                        new RuntimeException("git " + String.join(" ", args) + " timed out")))
                .runAsync();
        return future;
    }

    private static String sanitize(String repoName) {
        return repoName == null ? "unknown" : repoName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void deleteTempBase() {
        deleteDirectory(tempBase);
    }

    private void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            // On Windows, git pack files can be read-only; force-writeable before delete.
                            p.toFile().setWritable(true);
                            Files.delete(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            LOG.warn("Failed to delete directory {}: {}", dir, e.getMessage());
        }
    }
}
