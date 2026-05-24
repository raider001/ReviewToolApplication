package com.kalynx.serverlessreviewtool.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Manages a local bare git object store for a single remote repository's
 * {@code refs/heads/kalynx-reviews} orphan branch.
 *
 * <p>All review data is stored as individual files on the orphan branch. Reads
 * use {@code git cat-file blob}, writes use {@code git hash-object} + {@code git fast-import},
 * and listing uses {@code git ls-tree}. No third-party libraries — only subprocess git.
 *
 * <p>One instance corresponds to one remote repository URL.
 * Bare repos live at {@code <baseDir>/<repoName>.reviews.git/}.
 *
 * <h3>File layout on the branch</h3>
 * <pre>
 * reviews/
 *   &lt;reviewId&gt;/
 *     metadata/title
 *     metadata/status
 *     metadata/author
 *     ...
 *     reviewers/&lt;name&gt;
 *     comments/&lt;commentId&gt;/metadata
 *     comments/&lt;commentId&gt;/text
 *     comments/&lt;commentId&gt;/status
 * </pre>
 *
 * <h3>Thread safety</h3>
 * <p>All public methods are safe to call from any thread. A dedicated single-thread
 * executor serialises writes within a single JVM process; concurrent remote writers
 * are handled by push rejection + retry (up to {@value #MAX_WRITE_RETRIES} attempts).
 */
public class OrphanBranchStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrphanBranchStore.class);

    public static final String REVIEWS_BRANCH = "refs/heads/kalynx-reviews";
    /** Short name used in {@code ref:path} syntax for git commands. */
    private static final String BRANCH_SHORT = "kalynx-reviews";
    private static final int MAX_WRITE_RETRIES = 3;
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(60);

    private static final long FETCH_CACHE_TTL_MS = 30_000;

    private final String remoteUrl;
    private final Path bareDir;
    private final Executor executor;
    private volatile boolean initialized = false;

    // Accessed only from the single-thread executor — no volatile needed.
    private long lastFetchTimeMs = 0;

    /**
     * Creates a store backed by a bare git repo under {@code baseDir}.
     *
     * @param remoteUrl canonical git URL of the upstream repository
     * @param baseDir   directory under which the bare repo will be created
     */
    public OrphanBranchStore(String remoteUrl, Path baseDir) {
        this.remoteUrl = remoteUrl;
        String repoName = deriveRepoName(remoteUrl);
        this.bareDir = baseDir.resolve(repoName + ".reviews.git");
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "OrphanBranchStore-" + repoName);
            t.setDaemon(true);
            return t;
        });
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Reads the bytes of {@code reviews/<reviewId>/<streamPath>} from the branch tip.
     * Returns an empty {@link Optional} when the file does not exist.
     */
    public CompletableFuture<Optional<byte[]>> readFile(String reviewId, String streamPath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureInit();
                fetchTipCached();
                return readBlob(reviewId, streamPath);
            } catch (Exception e) {
                LOGGER.warn("readFile failed [{}/{}]: {}", reviewId, streamPath, e.getMessage());
                return Optional.empty();
            }
        }, executor);
    }

    /**
     * Reads multiple stream paths for a single review in one {@code git cat-file --batch}
     * subprocess call instead of one subprocess per path.
     *
     * @param reviewId    review to read
     * @param streamPaths paths relative to {@code reviews/<reviewId>/} to read
     * @return future containing a map from stream path to raw blob bytes;
     *         paths that do not exist on the branch map to an empty Optional
     */
    public CompletableFuture<Map<String, Optional<byte[]>>> readAllFiles(String reviewId,
                                                                          List<String> streamPaths) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureInit();
                fetchTipCached();
                return readBlobsBatch(reviewId, streamPaths);
            } catch (Exception e) {
                LOGGER.warn("readAllFiles failed for {}: {}", reviewId, e.getMessage());
                Map<String, Optional<byte[]>> empty = new LinkedHashMap<>();
                streamPaths.forEach(p -> empty.put(p, Optional.empty()));
                return empty;
            }
        }, executor);
    }

    /**
     * Writes {@code content} to {@code reviews/<reviewId>/<streamPath>} in a single commit.
     * Retries up to {@value #MAX_WRITE_RETRIES} times on push rejection.
     */
    public CompletableFuture<Void> writeFile(String reviewId, String streamPath, byte[] content) {
        return writeFiles(reviewId, Map.of(streamPath, content));
    }

    /**
     * Atomically writes all {@code pathToContent} entries under {@code reviews/<reviewId>/}
     * in a single commit and push. Retries on non-fast-forward push rejection.
     */
    public CompletableFuture<Void> writeFiles(String reviewId, Map<String, byte[]> pathToContent) {
        return CompletableFuture.runAsync(() -> {
            try {
                ensureInit();
                writeWithRetry(reviewId, pathToContent, MAX_WRITE_RETRIES);
            } catch (Exception e) {
                throw new RuntimeException("writeFiles failed for review " + reviewId, e);
            }
        }, executor);
    }

    /**
     * Lists all review IDs by walking the {@code reviews/} tree one level deep.
     */
    public CompletableFuture<List<String>> listReviewIds() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureInit();
                fetchTipCached();
                return listReviews();
            } catch (Exception e) {
                LOGGER.warn("listReviewIds failed: {}", e.getMessage());
                return List.of();
            }
        }, executor);
    }

    /**
     * Lists all comment IDs for a review by walking the {@code reviews/<reviewId>/comments/} subtree.
     * Returns an empty list when no comments exist yet.
     */
    public CompletableFuture<List<String>> listCommentIds(String reviewId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureInit();
                fetchTipCached();
                return listComments(reviewId);
            } catch (Exception e) {
                LOGGER.warn("listCommentIds failed for {}: {}", reviewId, e.getMessage());
                return List.of();
            }
        }, executor);
    }

    // -------------------------------------------------------------------------
    // Write path
    // -------------------------------------------------------------------------

    /**
     * Writes blobs, creates a commit via {@code git fast-import}, and pushes.
     * Retries on push rejection by re-fetching and rebuilding on the remote tip.
     */
    private void writeWithRetry(String reviewId, Map<String, byte[]> pathToContent, int retriesLeft)
            throws Exception {
        fetchTip(); // always fetch fresh before each attempt

        // Write each blob into the object store via git hash-object -w --stdin
        Map<String, String> pathToSha = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : pathToContent.entrySet()) {
            String sha = gitHashObject(entry.getValue());
            pathToSha.put(entry.getKey(), sha);
        }

        // Commit all blobs in one shot via git fast-import
        String fastImportStream = buildFastImportStream(reviewId, pathToSha);
        gitWithStdin(fastImportStream, "git", "fast-import", "--quiet").join();

        // Push to remote
        boolean pushed = gitPush();
        if (!pushed) {
            if (retriesLeft > 0) {
                LOGGER.debug("Push rejected for review '{}', retrying ({} left)", reviewId, retriesLeft);
                writeWithRetry(reviewId, pathToContent, retriesLeft - 1);
            } else {
                throw new RuntimeException("Push repeatedly rejected for review " + reviewId);
            }
        }
    }

    /**
     * Writes a blob via {@code git hash-object -w --stdin} and returns its SHA-1.
     */
    private String gitHashObject(byte[] content) throws Exception {
        String contentStr = new String(content, StandardCharsets.UTF_8);
        CompletableFuture<String> f = new CompletableFuture<>();
        ProcessUtils.runProcess("git", "hash-object", "-w", "--stdin")
                .workingDirectory(bareDir)
                .stdin(contentStr)
                .timeout(GIT_TIMEOUT)
                .onSuccess(out -> f.complete(out.trim()))
                .onFailure(err -> f.completeExceptionally(
                        new RuntimeException("git hash-object failed: " + err)))
                .onTimeout(() -> f.completeExceptionally(
                        new RuntimeException("git hash-object timed out")))
                .runAsync();
        return f.join();
    }

    /**
     * Builds the git fast-import stream that creates one commit updating the specified blobs.
     *
     * <p>When the branch already exists, resolves its current tip to a commit SHA and uses
     * {@code from <sha>} so that only the explicitly listed files are changed and all other
     * files under the branch are preserved.  Using the raw SHA (not the branch ref name)
     * avoids git's "can't create a branch from itself" restriction.
     *
     * <p>When the branch does not yet exist, omitting the {@code from} line tells fast-import
     * to start a new orphan commit.
     */
    private String buildFastImportStream(String reviewId, Map<String, String> pathToSha)
            throws Exception {
        // Resolve the branch to its current tip SHA (if it exists) so the fast-import
        // "from" line uses the commit object, not the mutable ref name.
        String tipSha = gitRevParseTip(REVIEWS_BRANCH);

        String message = "Update reviews/" + reviewId + "\n";
        int messageByteLen = message.getBytes(StandardCharsets.UTF_8).length;

        StringBuilder sb = new StringBuilder();
        sb.append("commit ").append(REVIEWS_BRANCH).append("\n");
        sb.append("committer ServerlessReviewTool <srt@localhost> ")
          .append(Instant.now().getEpochSecond()).append(" +0000\n");
        sb.append("data ").append(messageByteLen).append("\n");
        sb.append(message);
        if (tipSha != null) {
            sb.append("from ").append(tipSha).append("\n");
        }
        for (Map.Entry<String, String> entry : pathToSha.entrySet()) {
            sb.append("M 100644 ").append(entry.getValue())
              .append(" reviews/").append(reviewId).append("/").append(entry.getKey()).append("\n");
        }
        sb.append("\n"); // blank line terminates the commit command
        return sb.toString();
    }

    /**
     * Resolves a ref to its commit SHA. Returns {@code null} if the ref does not exist.
     */
    private String gitRevParseTip(String ref) {
        CompletableFuture<String> f = new CompletableFuture<>();
        ProcessUtils.runProcess("git", "rev-parse", "--verify", ref)
                .workingDirectory(bareDir)
                .timeout(GIT_TIMEOUT)
                .onSuccess(out -> f.complete(out.trim()))
                .onFailure(err -> f.complete(null))
                .onTimeout(() -> f.complete(null))
                .runAsync();
        return f.join();
    }

    /**
     * Pushes {@code refs/heads/kalynx-reviews} to origin.
     *
     * @return {@code true} if the push succeeded; {@code false} on non-fast-forward rejection
     * @throws RuntimeException for any other push failure
     */
    private boolean gitPush() throws Exception {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        ProcessUtils.runProcess("git", "push", "origin", REVIEWS_BRANCH)
                .workingDirectory(bareDir)
                .timeout(GIT_TIMEOUT)
                .onSuccess(out -> f.complete(true))
                .onFailure(err -> {
                    if (err.contains("rejected") || err.contains("non-fast-forward")
                            || err.contains("[rejected]")) {
                        f.complete(false); // retryable push conflict
                    } else {
                        f.completeExceptionally(new RuntimeException("git push failed: " + err));
                    }
                })
                .onTimeout(() -> f.completeExceptionally(
                        new RuntimeException("git push timed out")))
                .runAsync();
        return f.join();
    }

    // -------------------------------------------------------------------------
    // Read path
    // -------------------------------------------------------------------------

    /**
     * Runs {@code git cat-file --batch} to fetch all requested paths in one subprocess.
     * Output format per entry: {@code <sha> blob <size>\n<content bytes>\n} or {@code <obj> missing\n}.
     */
    private Map<String, Optional<byte[]>> readBlobsBatch(String reviewId, List<String> streamPaths)
            throws Exception {
        StringBuilder stdin = new StringBuilder();
        for (String path : streamPaths) {
            stdin.append(BRANCH_SHORT).append(":reviews/").append(reviewId).append("/").append(path).append("\n");
        }

        ProcessBuilder pb = new ProcessBuilder("git", "cat-file", "--batch")
                .directory(bareDir.toFile());
        Process process = pb.start();

        // Write stdin on a separate thread to avoid deadlock if the pipe buffer fills.
        byte[] stdinBytes = stdin.toString().getBytes(StandardCharsets.UTF_8);
        CompletableFuture.runAsync(() -> {
            try {
                process.getOutputStream().write(stdinBytes);
                process.getOutputStream().close();
            } catch (IOException ignored) {
            }
        });

        // Drain stderr so the subprocess never blocks on it.
        CompletableFuture.runAsync(() -> {
            try { process.getErrorStream().transferTo(java.io.OutputStream.nullOutputStream()); }
            catch (IOException ignored) { }
        });

        byte[] output;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             InputStream in = process.getInputStream()) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                baos.write(buf, 0, read);
            }
            output = baos.toByteArray();
        }

        boolean finished = process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("git cat-file --batch timed out for " + reviewId);
        }

        return parseBatchOutput(streamPaths, output);
    }

    /**
     * Parses the raw byte output of {@code git cat-file --batch}.
     * Uses byte offsets (not char offsets) so multi-byte UTF-8 content is handled correctly.
     */
    private static Map<String, Optional<byte[]>> parseBatchOutput(List<String> streamPaths, byte[] output) {
        Map<String, Optional<byte[]>> result = new LinkedHashMap<>();
        int pathIdx = 0;
        int pos = 0;

        while (pos < output.length && pathIdx < streamPaths.size()) {
            int lineEnd = indexOfByte(output, (byte) '\n', pos);
            if (lineEnd < 0) break;

            String header = new String(output, pos, lineEnd - pos, StandardCharsets.UTF_8);
            pos = lineEnd + 1;

            String streamPath = streamPaths.get(pathIdx++);

            if (header.endsWith(" missing")) {
                result.put(streamPath, Optional.empty());
            } else {
                // Header format: "<sha> blob <size>"
                int lastSpace = header.lastIndexOf(' ');
                int size = Integer.parseInt(header.substring(lastSpace + 1));
                byte[] content = Arrays.copyOfRange(output, pos, pos + size);
                pos += size + 1; // +1 skips the blank-line separator git appends after each blob
                result.put(streamPath, Optional.of(content));
            }
        }

        // Any remaining paths had no output (branch didn't exist, etc.)
        while (pathIdx < streamPaths.size()) {
            result.put(streamPaths.get(pathIdx++), Optional.empty());
        }

        return result;
    }

    private static int indexOfByte(byte[] bytes, byte target, int from) {
        for (int i = from; i < bytes.length; i++) {
            if (bytes[i] == target) return i;
        }
        return -1;
    }

    /**
     * Reads the blob at {@code reviews/<reviewId>/<streamPath>} on the branch tip.
     */
    private Optional<byte[]> readBlob(String reviewId, String streamPath) {
        String refPath = BRANCH_SHORT + ":reviews/" + reviewId + "/" + streamPath;
        CompletableFuture<Optional<byte[]>> f = new CompletableFuture<>();
        ProcessUtils.runProcess("git", "cat-file", "blob", refPath)
                .workingDirectory(bareDir)
                .timeout(GIT_TIMEOUT)
                .onSuccess(out -> f.complete(Optional.of(out.getBytes(StandardCharsets.UTF_8))))
                .onFailure(err -> f.complete(Optional.empty())) // path doesn't exist
                .onTimeout(() -> f.complete(Optional.empty()))
                .runAsync();
        return f.join();
    }

    /**
     * Lists immediate children of the {@code reviews/} directory on the branch tip.
     * Each child represents one review ID.
     */
    private List<String> listReviews() {
        String treeRef = BRANCH_SHORT + ":reviews";
        CompletableFuture<List<String>> f = new CompletableFuture<>();
        ProcessUtils.runProcess("git", "ls-tree", "--name-only", treeRef)
                .workingDirectory(bareDir)
                .timeout(GIT_TIMEOUT)
                .onSuccess(out -> {
                    List<String> ids = new ArrayList<>();
                    for (String line : out.split("\n")) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty()) {
                            ids.add(trimmed);
                        }
                    }
                    f.complete(ids);
                })
                .onFailure(err -> f.complete(List.of())) // reviews/ not yet present
                .onTimeout(() -> f.complete(List.of()))
                .runAsync();
        return f.join();
    }

    /**
     * Lists immediate children of {@code reviews/<reviewId>/comments/} on the branch tip.
     * Each child is a comment ID directory.
     */
    private List<String> listComments(String reviewId) {
        String treeRef = BRANCH_SHORT + ":reviews/" + reviewId + "/comments";
        CompletableFuture<List<String>> f = new CompletableFuture<>();
        ProcessUtils.runProcess("git", "ls-tree", "--name-only", treeRef)
                .workingDirectory(bareDir)
                .timeout(GIT_TIMEOUT)
                .onSuccess(out -> {
                    List<String> ids = new ArrayList<>();
                    for (String line : out.split("\n")) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty()) {
                            ids.add(trimmed);
                        }
                    }
                    f.complete(ids);
                })
                .onFailure(err -> f.complete(List.of())) // comments/ subtree not present yet
                .onTimeout(() -> f.complete(List.of()))
                .runAsync();
        return f.join();
    }

    // -------------------------------------------------------------------------
    // Fetch
    // -------------------------------------------------------------------------

    /**
     * Like {@link #fetchTip()} but skips the network call if a fetch completed within
     * {@value #FETCH_CACHE_TTL_MS} ms. Called from reads; writes always use {@link #fetchTip()}.
     *
     * Must only be called from tasks submitted to {@link #executor} (single-threaded).
     */
    private void fetchTipCached() {
        long now = System.currentTimeMillis();
        if (now - lastFetchTimeMs < FETCH_CACHE_TTL_MS) {
            return;
        }
        fetchTip();
        lastFetchTimeMs = System.currentTimeMillis();
    }

    /**
     * Fetches the latest tip of {@code refs/heads/kalynx-reviews} from origin.
     * Errors are swallowed because the branch may not exist on the remote yet.
     */
    private void fetchTip() {
        CompletableFuture<Void> f = new CompletableFuture<>();
        ProcessUtils.runProcess("git", "fetch", "origin",
                        "+refs/heads/kalynx-reviews:refs/heads/kalynx-reviews")
                .workingDirectory(bareDir)
                .timeout(GIT_TIMEOUT)
                .onSuccess(out -> f.complete(null))
                .onFailure(err -> {
                    // Acceptable: branch doesn't exist on remote yet
                    boolean notFound = err.contains("couldn't find remote ref")
                            || err.contains("does not appear to be a git repository")
                            || err.contains("not found");
                    if (!notFound) {
                        LOGGER.debug("fetchTip soft failure for {}: {}", remoteUrl, err);
                    }
                    f.complete(null);
                })
                .onTimeout(() -> {
                    LOGGER.warn("fetchTip timed out for {}", remoteUrl);
                    f.complete(null);
                })
                .runAsync();
        f.join();
    }

    // -------------------------------------------------------------------------
    // Initialisation
    // -------------------------------------------------------------------------

    /**
     * Lazily initialises the bare repo directory on first use.
     * Idempotent — safe to call on every operation.
     */
    private void ensureInit() throws IOException, InterruptedException {
        if (initialized) return;
        synchronized (this) {
            if (initialized) return;
            initBareRepo();
            initialized = true;
        }
    }

    private void initBareRepo() throws IOException, InterruptedException {
        if (!Files.exists(bareDir)) {
            Files.createDirectories(bareDir);

            Process init = new ProcessBuilder("git", "init", "--bare", bareDir.toAbsolutePath().toString())
                    .directory(bareDir.getParent().toFile())
                    .redirectErrorStream(true)
                    .start();
            int exitCode = init.waitFor();
            if (exitCode != 0) {
                throw new IOException("git init --bare failed (exit " + exitCode + ") at " + bareDir);
            }
            LOGGER.info("Initialised bare reviews store at {}", bareDir);
        }

        // Ensure remote 'origin' points at the right URL (idempotent).
        // Try set-url first (remote already exists); fall back to add (remote missing).
        Process setUrl = new ProcessBuilder("git", "remote", "set-url", "origin", remoteUrl)
                .directory(bareDir.toFile())
                .redirectErrorStream(true)
                .start();
        int setUrlExit = setUrl.waitFor();
        if (setUrlExit != 0) {
            // Remote doesn't exist yet — add it
            new ProcessBuilder("git", "remote", "add", "origin", remoteUrl)
                    .directory(bareDir.toFile())
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Runs a git command with stdin in the bare repo directory and returns a future
     * that completes with stdout on success or fails exceptionally otherwise.
     */
    private CompletableFuture<String> gitWithStdin(String stdin, String... args) {
        CompletableFuture<String> f = new CompletableFuture<>();
        ProcessUtils.runProcess(args)
                .workingDirectory(bareDir)
                .stdin(stdin)
                .timeout(GIT_TIMEOUT)
                .onSuccess(f::complete)
                .onFailure(err -> f.completeExceptionally(
                        new RuntimeException(Arrays.toString(args) + " failed: " + err)))
                .onTimeout(() -> f.completeExceptionally(
                        new RuntimeException(Arrays.toString(args) + " timed out")))
                .runAsync();
        return f;
    }

    private static String deriveRepoName(String remoteUrl) {
        String s = remoteUrl.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.length() > 80 ? s.substring(s.length() - 80) : s;
    }

    // -------------------------------------------------------------------------
    // Public types
    // -------------------------------------------------------------------------

    /**
     * A conflict detected when two concurrent writers updated the same file to different values.
     *
     * @param streamPath  path relative to {@code reviews/<reviewId>/}
     * @param localBytes  the bytes this client wanted to write
     * @param remoteBytes the bytes already on the remote
     */
    public record FileConflict(String streamPath, byte[] localBytes, byte[] remoteBytes) {}

    /**
     * Thrown when an unresolvable write conflict is detected.
     * The caller may inspect each {@link FileConflict} and retry with resolved content.
     */
    public static class ReviewConflictException extends RuntimeException {
        private final List<FileConflict> conflicts;

        public ReviewConflictException(List<FileConflict> conflicts) {
            super("Write conflict on " + conflicts.size() + " file(s)");
            this.conflicts = List.copyOf(conflicts);
        }

        public List<FileConflict> getConflicts() {
            return conflicts;
        }
    }
}
