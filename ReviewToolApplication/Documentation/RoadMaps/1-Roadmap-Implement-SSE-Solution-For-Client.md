# Plan: ReviewToolDefaultNotificationPlugin Overhaul + Notes-to-Branch Pivot

## Context

Two coupled changes:

1. **Notification plugin overhaul** — The plugin already uses SSE correctly, but must be updated to consume the new event format from the interface spec (`review.created`, `review.updated`, `branch.updated`, `branch.deleted`). It must also call `GET /reviews` at startup to load the initial review list, carry `repositoryUrl` in the notification payload, and signal the app to do targeted git fetches rather than triggering a full reload.

2. **Git notes → orphan branch pivot** — Replace all NDJSON-stream git notes (`refs/notes/reviews/*`) with a `refs/heads/kalynx-reviews` orphan branch per repository. All reads and writes use JGit plumbing directly against a local bare object store — no full clone, no checkout, no temp files. Reads use `TreeWalk.forPath` + `ObjectReader.open().getCachedBytes()` (blob into memory). Writes use `ObjectInserter` + `TreeFormatter` chain + `CommitBuilder` + `RefUpdate`.

These are coupled: the orphan branch makes reviews push via standard branch webhooks (the indexer can detect them natively), and the notification plugin becomes the trigger for targeted single-review git fetches rather than full-repository notes syncs.

---

## Current State (what changes from)

- `GitImpl.cloneRepository()` → full clone + `notes.mergeStrategy=union` config + `fetchNotes`
- `GitReviewNotesManager` → 1035-line class: subprocess git notes add/push/fetch, NDJSON streams written to temp files under `java.io.tmpdir`
- `ReviewItemLoader.loadReviewsFromRepository()` → `git fetch` all branches + notes, `git show-ref` to list review IDs, then `readAllMetadataFromLocal()` per review
- `ReviewItemManager.applyNotificationUpdates()` → triggers full `refreshRepository()` for every affected repo on any SSE event
- `DefaultNotificationPlugin` → maps old event type strings (`REVIEW_CREATED`, `REVIEW_UPDATED`, `REVIEW_COMMENT_ADDED`, etc.), no `repositoryUrl` in payload
- No `GET /reviews` REST call anywhere in the client

---

## Architecture After Change

```
Plugin (startup):
  GET /reviews → fire CREATED events per review (with repositoryUrl)
  GET /events/stream → incremental SSE updates

Plugin (on SSE event):
  Map event type → ReviewListUpdate (with reviewId + repositoryUrl + branchName)
  → onReviewUpdated(update)

App (on notification):
  Use OrphanBranchStore.readSingleReview(repositoryUrl, reviewId)
  Upsert into reviewItems → notify UI listeners

OrphanBranchStore (JGit plumbing, per remote):
  Bare repo at <gitLocalPath>/<repoName>.reviews.git/
  fetch: --filter=blob:none --depth=1 refs/heads/kalynx-reviews
  read: TreeWalk.forPath → ObjectReader.open(blobId).getCachedBytes()
  write: ObjectInserter(blob) → TreeFormatter chain → CommitBuilder → RefUpdate + push
  conflict: detect rejected push → re-fetch tip → retry (max 3)
```

---

## Implementation Steps

### Step 1 — Extend `ReviewListUpdate` (ReviewToolPluginInterface)

**File:** `ReviewToolPluginInterface/src/main/java/com/kalynx/serverlessreviewtool/plugin/ReviewListUpdate.java`

Add two nullable fields to the record:
```java
public record ReviewListUpdate(
    String eventId,
    Instant occurredAt,
    ReviewUpdateType updateType,
    String reviewId,
    String primaryRepository,
    List<String> repositories,
    String repositoryUrl,    // canonical git URL — null if unknown
    String branchName        // non-null only for branch.* events
) implements NotificationPayload {}
```

All existing construction sites pass `null, null` for the new fields — no breakage.

---

### Step 2 — Update `IndexerSseListener` (notification plugin)

**File:** `ReviewToolDefaultNotificationPlugin/.../IndexerSseListener.java`

**2a. Simplify `IndexerEvent` record** — replace the existing record with:
```java
public record IndexerEvent(
    long sequenceNo,
    String eventType,     // e.g. "review.created", "branch.updated"
    String reviewId,      // from JSON "review_id"
    String repository,    // from JSON "repository" (may be absent for review.* events)
    String repositoryUrl, // from JSON "repository_url" (branch.* events only)
    String branchName     // from JSON "branch_name" (branch.* events only)
) {}
```

**2b. Update `parseEvent()`** — adapt JSON extraction:
- `review_id` → `reviewId`
- `repository_url` → `repositoryUrl` (null if absent)
- `branch_name` → `branchName` (null if absent)
- Drop `actorUser`, `deliveryId`, `timestamp` (not in new payload format)

**2c. Update cursor URL** — `?since=<cursor>` (already works); `Last-Event-ID` header kept as-is.

---

### Step 3 — Add `IndexerRestClient` (notification plugin)

**New file:** `ReviewToolDefaultNotificationPlugin/.../IndexerRestClient.java`

Calls `GET /reviews` against the configured indexer URL. Returns a list of `ReviewItem` DTOs containing `review_id`, `status`, `last_updated`, `review_branch`, `base_branch`, and `repositories[]{repository, repository_url}`.

```java
public List<ReviewSummary> fetchReviews(IndexerConfig config) { ... }

public record ReviewSummary(
    String reviewId,
    String status,
    String reviewBranch,
    String baseBranch,
    List<RepositoryRef> repositories
) {}

public record RepositoryRef(String repository, String repositoryUrl) {}
```

Uses same `java.net.http.HttpClient` pattern already used by `IndexerSseListener`. Gson for JSON parsing.

---

### Step 4 — Update `DefaultNotificationPlugin` (notification plugin)

**File:** `ReviewToolDefaultNotificationPlugin/.../DefaultNotificationPlugin.java`

**4a. `initialize()`** — add REST call before opening SSE stream:
```java
public void initialize() {
    IndexerConfig config = new IndexerConfigLoader().load();
    List<ReviewSummary> reviews = new IndexerRestClient().fetchReviews(config);
    reviews.forEach(r -> fireReviewCreated(r, config)); // builds ReviewListUpdate with repositoryUrl
    notifyRepositoriesUpdated(config, reviews);
    startListeners(config);
}
```

**4b. `mapEventType()`** — new event type strings:
```java
case "review.created"              -> ReviewUpdateType.CREATED;
case "review.updated"              -> ReviewUpdateType.UPDATED;
case "branch.updated"              -> ReviewUpdateType.UPDATED;
case "branch.deleted"              -> ReviewUpdateType.DELETED;
```

**4c. `onIndexerEvent()`** — populate `repositoryUrl`:
- For `branch.*` events: use `event.repositoryUrl()` directly from payload
- For `review.*` events: look up location from `config.repositories()` by matching `event.repository()`

```java
String repoUrl = event.repositoryUrl() != null
    ? event.repositoryUrl()
    : config.repositories().stream()
        .filter(r -> r.name().equals(event.repository()))
        .map(IndexerConfig.RepositoryEntry::location)
        .findFirst().orElse(null);

ReviewListUpdate update = new ReviewListUpdate(
    eventId, Instant.now(), type,
    event.reviewId(), event.repository(), List.of(event.repository()),
    repoUrl, event.branchName()
);
```

**4d. `onConfigurationChanged()`** — reload + re-fire initial review list (re-calls `GET /reviews`).

---

### Step 5 — Clean up `IndexerConfig` (notification plugin)

**File:** `ReviewToolDefaultNotificationPlugin/.../IndexerConfig.java`

Remove `pollIntervalMs` / `pollIntervalSeconds` field — it served no purpose in the SSE model and now has no meaning at all.

**File:** `RepositoriesManagementPanel.java` — remove poll interval label and field from the Swing form.

---

### Step 6 — Add JGit dependency

**File:** `ReviewToolApplication/pom.xml`

```xml
<dependency>
    <groupId>org.eclipse.jgit</groupId>
    <artifactId>org.eclipse.jgit</artifactId>
    <version>7.2.0.202501071729-r</version>
</dependency>
```

---

### Step 7 — Create `OrphanBranchStore` (application)

**New file:** `ReviewToolApplication/src/main/java/com/kalynx/serverlessreviewtool/git/OrphanBranchStore.java`

One instance per remote URL. Maintains a bare `Repository` at `<gitLocalPath>/<repoName>.reviews.git/`.

**Key methods:**

```java
// Returns bytes of the file at reviews/<reviewId>/<streamPath>, or empty if not found.
CompletableFuture<Optional<byte[]>> readFile(String reviewId, String streamPath)

// Writes content to reviews/<reviewId>/<streamPath>. Retries on push conflict.
CompletableFuture<Void> writeFile(String reviewId, String streamPath, byte[] content)

// Atomically writes multiple files under reviews/<reviewId>/ in one commit.
CompletableFuture<Void> writeFiles(String reviewId, Map<String, byte[]> pathToContent)

// Lists all review IDs by walking the reviews/ tree one level deep.
CompletableFuture<List<String>> listReviewIds()
```

**Read path (JGit):**
```
1. fetchTip() — Transport with DepthFetch=1, FilterSpec.withBlobLimit(0)
   → fetches commit + tree objects only, no blobs
2. resolve("refs/heads/kalynx-reviews") → tipCommit
3. RevWalk.parseCommit(tipCommit).getTree() → rootTree
4. TreeWalk.forPath(reader, "reviews/<id>/<path>", rootTree) → blobId
5. ObjectReader.open(blobId).getCachedBytes() → byte[] directly in memory
```

**Write path (JGit):**
```
1. fetchTip() — same as read path
2. For each changed file: ObjectInserter.insert(OBJ_BLOB, content)
3. Reconstruct tree chain bottom-up:
   - Read existing tree at each ancestor dir via TreeWalk
   - Build new TreeFormatter replacing only changed entries
   - ObjectInserter.insert(OBJ_TREE, treeFormatter)
4. CommitBuilder: set tree=newRoot, parent=currentTip, author, message
   → ObjectInserter.insert(OBJ_COMMIT, commitBuilder)
5. RefUpdate: setNewObjectId(newCommit), setExpectedOldObjectId(currentTip)
   → forceUpdate() on local ref
6. Push via Transport.openPush() with RefSpec kalynx-reviews → origin
7. On rejection (non-fast-forward): re-fetchTip(), retry up to 3 times
```

**Conflict resolution on push rejection:**

Each file is a single logical value. Since files are per-field, two users editing *different* fields never conflict — their pushes land on different blobs in the tree and the second push just needs a tree/commit rebuild on the updated tip (the retry loop handles this automatically). A true conflict only occurs when two users write the *same* file simultaneously.

```
Retry loop (max 3):
  1. Push rejected (non-fast-forward)
  2. Re-fetch tip → new rootTree
  3. For each file being written:
     a. Read remote's current blob for that path (TreeWalk.forPath → getCachedBytes)
     b. If remote blob == expected baseline (we raced another push on an unrelated file):
        → simply re-apply our blob on the new tree → retry push
     c. If remote blob != expected baseline AND remote blob != our new content:
        → CONFLICT on this file: record (path, localBytes, remoteBytes)
  4. If no conflicts: rebuild tree with all our changes on new tip → commit → push
  5. If conflicts exist: throw ConflictException(List<FileConflict>)
```

`OrphanBranchReviewManager` catches `ConflictException` and:
- **Auto-resolvable** (e.g., `reviewers` JSON: different reviewer keys edited): merge the two JSON objects by union → retry write
- **Unresolvable** (same scalar field edited differently): surface `ReviewConflictEvent` to the application via a callback. App shows a Swing dialog listing each conflicting file with local/remote value — user picks one. No author or timestamp change needed (both are already captured in the NDJSON line's existing fields and in the commit metadata).

**New interface for conflict notification:**
```java
// in ReviewToolPluginInterface or application-local
public record FileConflict(String streamPath, byte[] localValue, byte[] remoteValue) {}
public interface ConflictResolver {
    CompletableFuture<byte[]> resolve(FileConflict conflict); // returns chosen bytes
}
```

`OrphanBranchReviewManager` takes an optional `ConflictResolver` at construction time; the application wires it to a Swing dialog.

**Bare repo initialization:**
```java
// Lazy-init per remoteUrl, thread-safe
Repository repo = new FileRepositoryBuilder()
    .setBare()
    .setGitDir(bareDir.toFile())
    .build();
repo.create(true);  // only if not already created
StoredConfig config = repo.getConfig();
config.setString("remote", "origin", "url", remoteUrl);
config.setString("remote", "origin", "fetch", "+refs/heads/kalynx-reviews:refs/heads/kalynx-reviews");
config.save();
```

---

### Step 8 — Create `OrphanBranchReviewManager` (application)

**New file:** `ReviewToolApplication/src/main/java/com/kalynx/serverlessreviewtool/git/OrphanBranchReviewManager.java`

Replaces `GitReviewNotesManager.java`. Same public API (method names match) so call sites need only swap the type.

**File format on the orphan branch** — NDJSON, one line per file (current state only):
- Same line format as today's stream entries, written by `ReviewStreamHelper`
- `ReviewStreamHelper.read*()` returns a single-element list (one entry = current state)
- Git commit history serves as the audit log; no replay needed at read time

**Key methods mirror `GitReviewNotesManager`** but delegate to `OrphanBranchStore`. Each stream file holds exactly one NDJSON line (current state). On write, `ReviewStreamHelper` produces that single line and `OrphanBranchStore.writeFile()` replaces the blob entirely.

```java
createReview(reviewId, editor, title, author, ...) →
  // Use ReviewStreamHelper to produce single-line NDJSON per field
  // Then OrphanBranchStore.writeFiles() in one commit (all paths → blobs → tree → commit → push)

readAllMetadata(reviewId) →
  parallel reads via OrphanBranchStore.readFile() for each stream path
  → ReviewStreamHelper.read*() on each byte[] (returns 1-element list)
  → reconstruct ReviewMetadata record (same shape as today)
```

**Keep `ReviewStreamHelper`** for both reading and writing. Each file contains exactly **one NDJSON line** (current state — git history is the log). No append; each write replaces the entire file with a single new line. `StreamEntry` wrappers are retained as-is — callers see a single-element list on read instead of a stream.

---

### Step 9 — Update `ReviewItemLoader` (application)

**File:** `ReviewToolApplication/src/main/java/com/kalynx/serverlessreviewtool/git/ReviewItemLoader.java`

**Changes:**
- Replace `synchronizeRepository()` (which called `git.fetch()` + notes) with no-op or targeted `OrphanBranchStore.fetchTip()`
- Replace `listReviewIds()` (`git show-ref` against `refs/notes/*`) with `OrphanBranchStore.listReviewIds()`
- Replace `loadReviewItem()` (notes-based metadata read) with `OrphanBranchReviewManager.readAllMetadata()`
- Add `remoteUrl` parameter to `loadReviewsFromRepository(String repositoryName, String remoteUrl)`

The call-site in `ReviewItemManager.refreshRepository()` already has `descriptor.location()` — pass it through.

---

### Step 10 — Update `ReviewItemManager` (application)

**File:** `ReviewToolApplication/src/main/java/com/kalynx/serverlessreviewtool/managers/ReviewItemManager.java`

**10a. `applyNotificationUpdates()`** — targeted fetch instead of full refresh:

```java
public void applyNotificationUpdates(ReviewListUpdate[] updates) {
    for (ReviewListUpdate update : updates) {
        if (update.reviewId() != null && update.repositoryUrl() != null) {
            // Targeted: fetch only this review's blob from the orphan branch
            fetchSingleReview(update.reviewId(), update.repositoryUrl(),
                              update.primaryRepository(), update.updateType());
        } else if (update.primaryRepository() != null) {
            // Fallback if URL not known: full refresh
            refreshRepository(update.primaryRepository());
        }
    }
}
```

**10b. New `fetchSingleReview()`** — uses `OrphanBranchReviewManager`:
```java
private CompletableFuture<Void> fetchSingleReview(
        String reviewId, String remoteUrl, String repositoryName, ReviewUpdateType type) {
    if (type == ReviewUpdateType.DELETED) {
        removeReviewFromSnapshot(repositoryName, reviewId);
        notifyListeners();
        return CompletableFuture.completedFuture(null);
    }
    return orphanReviewManager(remoteUrl)
        .readAllMetadata(reviewId)
        .thenAccept(snapshot -> {
            ReviewItem item = ReviewItem.fromSnapshot(reviewId, repositoryName, snapshot);
            upsertReview(repositoryName, item);
            notifyListeners();
        });
}
```

---

### Step 11 — Clean up `GitImpl` (application)

**File:** `ReviewToolApplication/src/main/java/com/kalynx/serverlessreviewtool/git/GitImpl.java`

Remove notes-specific code:
- `fetchNotes(Path repoPath)` — remove (called in `cloneRepository`, `fetch`, `pull`)
- `configureNotesMergeStrategy(Path repoPath)` — remove (called in `cloneRepository`)
- Remove notes-related calls from `cloneRepository()`, `fetch()`, `pull()`
- Remove `pushNotes(String repository, List<String> notes)` from the `Git` interface and implementation

---

### Step 12 — Migration utility (optional, run once)

**New file:** `ReviewToolApplication/src/main/java/com/kalynx/serverlessreviewtool/git/ReviewMigrator.java`

On first startup (check for `<gitLocalPath>/.reviews-migrated` marker):
1. For each configured repository: `git for-each-ref refs/notes/reviews/`
2. For each review ID found: read NDJSON streams via `ReviewStreamHelper` (multi-line; take last entry per field)
3. Write to orphan branch via `OrphanBranchReviewManager.writeFiles()` with the same single-line NDJSON format
4. Write `.reviews-migrated` marker when done

Migration is idempotent (CAS). Skips reviews already present on the orphan branch.

---

## File Map (critical files to touch)

| Module | File | Action |
|---|---|---|
| PluginInterface | `ReviewListUpdate.java` | Add `repositoryUrl`, `branchName` fields |
| NotificationPlugin | `IndexerSseListener.java` | New event format, simplified `IndexerEvent` |
| NotificationPlugin | `DefaultNotificationPlugin.java` | `GET /reviews` on init, new event mapping |
| NotificationPlugin | `IndexerRestClient.java` | **NEW** — REST client for `GET /reviews` |
| NotificationPlugin | `IndexerConfig.java` | Remove `pollIntervalMs` |
| NotificationPlugin | `RepositoriesManagementPanel.java` | Remove poll interval UI |
| Application | `pom.xml` | Add JGit dependency |
| Application | `git/OrphanBranchStore.java` | **NEW** — JGit plumbing (read/write/list) |
| Application | `git/OrphanBranchReviewManager.java` | **NEW** — review CRUD over orphan branch |
| Application | `git/ReviewMigrator.java` | **NEW** — one-shot migration from notes |
| Application | `git/ReviewItemLoader.java` | Swap notes ops for orphan branch ops |
| Application | `git/GitImpl.java` | Remove notes methods |
| Application | `managers/ReviewItemManager.java` | Targeted single-review fetch on notification |

---

## JGit Classes Used (reference)

| Need | JGit API |
|---|---|
| Bare repo | `FileRepositoryBuilder.setBare().setGitDir().build()` |
| Partial clone fetch | `Transport.openFetch()` + `DepthWalk` / `FilterSpec.withBlobLimit(0)` |
| Resolve ref | `repo.resolve("refs/heads/kalynx-reviews")` |
| Parse commit/tree | `RevWalk.parseCommit()`, `.getTree()` |
| Navigate tree | `TreeWalk.forPath(reader, path, treeId)` |
| Read blob (in memory) | `ObjectReader.open(blobId).getCachedBytes()` |
| Write blob | `ObjectInserter.insert(Constants.OBJ_BLOB, bytes.length, inputStream)` |
| Build tree | `TreeFormatter` → `ObjectInserter.insert(OBJ_TREE, formatter)` |
| Build commit | `CommitBuilder` → `ObjectInserter.insert(OBJ_COMMIT, builder)` |
| Update ref | `RefUpdate.setNewObjectId()` + `.forceUpdate()` |
| Push | `Transport.openPush()` + `RemoteRefUpdate` |

---

## Verification

1. **Unit tests for `OrphanBranchStore`** — use a local bare git repo + an in-process "remote" (another bare repo) to test read/write/conflict-retry without network
2. **Unit tests for `IndexerRestClient`** — mock `HttpClient`, verify JSON parsing
3. **Unit tests for `IndexerSseListener`** — existing tests; update to use new event format strings
4. **Integration smoke test** — launch a local indexer, connect notification plugin, verify:
    - `GET /reviews` fires on startup → review list populated
    - SSE `review.updated` event → targeted git fetch → UI updates
    - SSE `branch.updated` → diff refresh triggered
5. **Orphan branch write round-trip** — create review → verify `refs/heads/kalynx-reviews` on remote has correct tree → read back via `OrphanBranchStore` → compare

---

## Key Assumptions

- The indexer's SSE events for `review.created`/`review.updated` include a `repository` field (not shown in the minimal spec diagram, but required for the client to know where to fetch). If the indexer omits this, `repositoryUrl` will be null for those events and `applyNotificationUpdates()` falls back to a full `refreshRepository()`.
- The `GET /reviews` endpoint is already live (per the indexer exploration — `ReviewsIndexRepository` and the handler exist).
- The proposal's orphan branch name is `refs/heads/kalynx-reviews` (confirmed from the proposal doc).
- Migration is included as a one-shot utility. If no migration is needed (clean deployment), `ReviewMigrator` can be skipped.