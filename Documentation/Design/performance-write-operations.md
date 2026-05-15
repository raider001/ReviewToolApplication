# Performance Plan — Write Operations

## Scope

This document analyses the current performance characteristics of the key write
operations and proposes concrete improvements for each. It also covers workflow
correctness issues that were identified alongside the performance analysis.

| Operation | Entry point |
|---|---|
| Create review | `GitReviewNotesManager.createReview` / `createReviewAcrossRepositories` |
| Edit review (title / summary / status) | `ReviewContextManager.saveReviewMetadata` → `saveAllMetadataBatch` |
| Change reviewer status | `ReviewerManager.updateReviewerStatus` → `writeToStream` |
| Add / save comment | `ReviewCommentManager.saveComment` → 3× `writeToStream` |
| Resolve / unresolve comment | `ReviewCommentManager.saveComment` (status path) → `writeToStream` |
| Close review | `ReviewAuthorActionHandler.handleCloseReview` → snapshot + `saveReviewMetadata` |
| Request re-review | `ReviewerDecisionHandler.handleReRequestReview` → `updateReviewerStatus` |

---

## Common Patterns and Cross-Cutting Costs

Before per-operation analysis, the following patterns appear in **every** write path
and represent shared bottlenecks.

### P1 — `getRepositoryRootCommit()` called on every write

Every call to `writeToStream` and `saveAllMetadataBatch` calls
`getRepositoryRootCommit()`, which executes:

```
git rev-list --max-parents=0 HEAD
```

The root commit of a repository **never changes**. Calling this once per stream write
wastes ~30–80 ms per operation on a remote repository.  
**Fix:** Cache the result per repository name in `GitReviewNotesManager` in a
`ConcurrentHashMap<String, String>` field (or a lazy `CompletableFuture` memoised at
construction time via `git.executeAsync`).

### P2 — `collectExpectedRefStates` issues N individual `rev-parse` calls

`saveAllMetadataBatch` resolves each ref's current OID with a separate
`git rev-parse --verify <ref>` before writing. For 5 streams that is 5 sequential git
processes.  
**Fix:** Replace with a single `git for-each-ref --format=%(objectname) %(refname)
refs/notes/reviews/<reviewId>/...` call, parse the map in one shot, and default
missing refs to `ZERO_OID`.

### P3 — `forceResetAllFromRemote` on conflict issues N individual fetch calls

Each retry fetches each stream ref separately. For 5 streams on a conflict retry that
is 5 extra round-trips.  
**Fix:** Combine into a single `git fetch origin <ref1> <ref2> ...` with all conflict
refs in one invocation (same pattern as the existing `fetchAllNotes` batch).

---

## 1. Create Review

### Current flow

```
fetchAllNotes (9 refs, 1 batch fetch)
  └─ getRepositoryRootCommit()           ← P1
       └─ [write 9 files locally]
            └─ resolveAndNormalize × 9   ← file I/O
                 └─ addAllNotesToGit (9 parallel git-notes-add)
                      └─ pushAllNotes (1 push with 9 refs)
```

For multi-repository reviews, primary and secondary repositories run in parallel, but
each secondary runs its own full fetch → write → push cycle.

### Issues

| # | Issue | Impact |
|---|---|---|
| C1 | `getRepositoryRootCommit()` called once per repository | ~50 ms per repo |
| C2 | Secondary repos each issue a separate push to their own remote | N−1 additional round-trips |
| C3 | `resolveAndNormalize` reads and rewrites each file even for a brand-new review where there are no duplicates to collapse | Unnecessary I/O |

### Proposed improvements

- **C1** → Apply fix P1 (cache root commit).
- **C2** → No structural change needed. Secondary repo pushes are already parallel.
  Profile whether the secondary push time is significant before optimising further.
- **C3** → Skip `resolveAndNormalize` during initial creation: since the files are
  written from scratch there is nothing to deduplicate. Add a boolean parameter
  `skipNormalizeOnCreate` or move normalization inside `addAllNotesToGit` as an
  optional step.

---

## 2. Edit Review (title / summary / status / reviewer list)

### Current flow (`saveAllMetadataBatch`)

```
collectExpectedRefStates (5 × git rev-parse)   ← P2
  └─ getRepositoryRootCommit()                  ← P1
       └─ extractNoteToFile × 5 (parallel)
            └─ [write + resolveAndNormalize × 5]
                 └─ addAllNotesToGit × 5 (parallel)
                      └─ pushAllNotesWithLease (1 push)
                           [on conflict: forceResetAllFromRemote × 5 + retry]  ← P3
```

### Issues

| # | Issue | Impact |
|---|---|---|
| E1 | `collectExpectedRefStates` → 5 serial `rev-parse` calls (P2) | ~150–400 ms |
| E2 | `getRepositoryRootCommit()` not cached (P1) | ~50 ms |
| E3 | Conflict retry issues 5 individual fetches (P3) | Up to 400 ms extra on retry |
| E4 | `shouldWriteStringEntry` reads the extracted file a second time for comparison | Redundant read after extract |

### Proposed improvements

- **E1** → Apply fix P2 (`for-each-ref` batch OID resolution).
- **E2** → Apply fix P1.
- **E3** → Apply fix P3.
- **E4** → Pass the already-extracted `Path` into `shouldWriteStringEntry` consistently
  (already done for most paths) and avoid a second file read by reusing the content
  that was already loaded during `extractNoteToFile`.

---

## 3. Change Reviewer Status

### Current flow (`writeToStreamWithRetry` via `writeReviewer`)

```
resolveRefOidOrZero (1 × git rev-parse)
  └─ getAnchorCommit() → getRepositoryRootCommit()   ← P1
       └─ extractNoteToFile (1 × git notes show)
            └─ [write reviewer entry + resolveAndNormalize]
                 └─ addNotesToGit (1 × git notes add)
                      └─ pushNotesWithLease (1 × git push)
                           [on conflict: forceResetFromRemote + retry]
```

Total minimum: **4 sequential git operations** (~200–600 ms on a remote).

### Issues

| # | Issue | Impact |
|---|---|---|
| R1 | Every status change triggers a standalone `writeToStream` with its own fetch/extract/push cycle | 200–600 ms per change |
| R2 | `getRepositoryRootCommit()` not cached (P1) | ~50 ms |
| R3 | Reviewer status change only writes to the primary repository | Consistent with design; document this explicitly |

### Proposed improvements

- **R1** → Batch reviewer status changes through `saveAllMetadataBatch` when a status
  change is immediately followed by a metadata save (e.g., approve → reload). Since
  `saveAllMetadataBatch` already handles the `reviewers` stream alongside metadata
  streams, a single approve/reject action could be combined into one batch write
  instead of triggering `writeReviewer` separately and then reloading.
- **R2** → Apply fix P1.
- Consider exposing a dedicated `updateReviewerStatusBatch` method that accepts a list
  of reviewer changes and writes them all in one `writeToStream` cycle.

---

## 4. Add / Save Comment

### Current flow

```
saveComment
  └─ writeCommentMetadata → writeToStreamWithRetry   (fetch+extract+add+push)
       └─ .thenCompose
            └─ writeCommentText → writeToStreamWithRetry   (fetch+extract+add+push)
                 └─ .thenCompose  [if needsResolution or resolved]
                      └─ writeCommentStatus → writeToStreamWithRetry   (fetch+extract+add+push)
```

The three streams are **chained sequentially** (`thenCompose`). Each is a full
fetch-extract-write-add-push round-trip.  
Minimum cost: **3 × ~200 ms = ~600 ms** per comment on a remote repository.

`saveAllComments` fans out N comments in parallel (`allOf`), but each comment still
pays the 3-sequential cost internally.

### Issues

| # | Issue | Impact |
|---|---|---|
| S1 | Metadata, text, and status streams are sequential (`thenCompose`) | 3× latency vs. parallel |
| S2 | Each stream write is a separate push round-trip | 3 pushes per comment |
| S3 | `getRepositoryRootCommit()` called 3× per comment (P1) | ~150 ms per comment |
| S4 | `saveAllComments` is called on panel close with potentially many unsaved comments | Parallel fanout may saturate git subprocess pool |

### Proposed improvements

- **S1 (high priority)** → Parallelize the three stream writes. Metadata, text, and
  status use different git refs, so they are completely independent. Change
  `saveComment` to use `CompletableFuture.allOf(metadataFuture, textFuture, statusFuture)`.
- **S2 (high priority)** → Batch comment streams into a single `pushAllNotes` call.
  Introduce a `saveCommentBatch` method on `GitReviewNotesManager` modelled after
  `saveAllMetadataBatch`: collect all three (or more) comment stream paths, resolve
  their OIDs together, write all files, `addAllNotesToGit`, and push in one command.
- **S3** → Apply fix P1.
- **S4** → `saveAllComments` already parallelises at the comment level. After S2 is
  applied each comment will only cost one push, making the fanout acceptable.
  Consider an upper bound on the concurrent git subprocess count via a semaphore if
  there are stability concerns with very large comment sets.

---

## 5. Resolve / Unresolve Comment

### Current flow

Resolving triggers `saveComment` with `comment.isResolved() == true`, which appends
a `CommentStatusData` entry via `writeCommentStatus`. This follows the three-stream
path analysed in §4 above, with the same costs.

### Specific issue

| # | Issue | Impact |
|---|---|---|
| V1 | A resolve-only operation still re-writes metadata and text streams even when they have not changed | Two unnecessary pushes |

### Proposed improvements

- **V1** → Add a dedicated `resolveComment(reviewId, commentId, resolvedBy, resolved)`
  path in `ReviewCommentManager` that **only** calls `writeCommentStatus`. Skip the
  metadata and text writes entirely when the comment body and location have not
  changed. The caller (`ReviewCommentManager.saveComment`) should check whether the
  incoming comment differs from the existing one before writing each stream,
  analogous to `shouldWriteStringEntry` in `saveAllMetadataBatch`.

---

## 6. Close Review

### Current flow (`ReviewAuthorActionHandler.handleCloseReview`)

```
captureReviewCommitSnapshots (per-repository, sequential internally)
  └─ saveReviewMetadata → saveAllMetadataBatch
       ├─ collectExpectedRefStates (5 × git rev-parse)   ← P2
       ├─ getRepositoryRootCommit()                        ← P1
       ├─ extractNoteToFile × 5 (parallel)
       ├─ addAllNotesToGit × 5 (parallel)
       └─ pushAllNotesWithLease (1 push)
            [on conflict: forceResetAllFromRemote × 5 + retry]  ← P3
  └─ loadReviewMetadataOnly (fetch + read all streams)
```

Close is one of the most expensive author actions because it unconditionally captures
commit snapshots **before** the metadata write, adding at least one full git round-trip
per repository before the save begins.

### Issues

| # | Issue | Impact |
|---|---|---|
| CL1 | `captureReviewCommitSnapshots` is always called regardless of whether snapshots were already captured at a previous close/cancel attempt | Redundant snapshot writes on retry |
| CL2 | `getRepositoryRootCommit()` not cached (P1) | ~50 ms |
| CL3 | `collectExpectedRefStates` issues N individual `rev-parse` calls (P2) | ~150–400 ms |
| CL4 | Conflict retry issues N individual fetches (P3) | Up to 400 ms extra on retry |

### Proposed improvements

- **CL1** → Before calling `captureReviewCommitSnapshots`, check whether
  `loadLatestReviewCommits` already returns a non-empty snapshot for the current
  review state (branch + commit). If a snapshot already exists and the branch tip has
  not advanced since the last snapshot, skip the capture step entirely.
- **CL2** → Apply fix P1.
- **CL3** → Apply fix P2.
- **CL4** → Apply fix P3.

---

## 7. Request Re-review (Workflow Correctness Fix)

> **Note:** This section documents a correctness bug, not a performance issue.
> It is included here because it was identified during write-operation review and the
> fix touches the same handlers analysed above.

### Bug description

When the review author calls `handleReRequestReview(reviewerName)`, the handler:

1. Resets the named reviewer's status to `REVIEWING` via `updateReviewerStatus`.
2. Reloads the review metadata.
3. Updates the UI — **without recalculating the overall review status**.

#### Problem scenario

1. Review is in `CHANGES_REQUESTED` state because reviewer A requested changes.
2. Author addresses the feedback and calls re-request on reviewer A.
3. Reviewer A's status is reset to `REVIEWING` — no other reviewer has
   `CHANGES_REQUESTED`.
4. **Expected:** Overall status reverts to `IN_PROGRESS`.
5. **Actual:** Overall status remains `CHANGES_REQUESTED` because the handler
   does not call `computeOverallStatus` or write the corrected status.

### Root cause

`handleReRequestReview` only calls `updateReviewerStatus` + `loadReviewMetadataOnly`.
It does not mirror the status-synchronisation logic present in `applyReviewerDecision`,
which calls `computeOverallStatus` after loading the updated context and conditionally
saves a corrected overall status.

### Fix (implemented)

After the initial reviewer update and metadata reload,
`handleReRequestReview` now follows the same pattern as `applyReviewerDecision`:

```
updateReviewerStatus(REVIEWING)
  └─ loadReviewMetadataOnly
       └─ if !terminalStatus && computeOverallStatus() != current.status
            └─ saveReviewMetadata (corrected status)
                 └─ loadReviewMetadataOnly (final reload)
```

The `computeOverallStatus` rule is:
- If **any** reviewer still has `CHANGES_REQUESTED` → overall = `CHANGES_REQUESTED`.
- Otherwise → overall = `IN_PROGRESS`.

This ensures that re-requesting review on the last "blocking" reviewer correctly
transitions the ticket back to `IN_PROGRESS`.

### Performance impact of fix

The fix adds **at most one extra** `saveReviewMetadata + loadReviewMetadataOnly` round-trip
when the overall status needs to change. This is identical to the existing cost paid by
`applyReviewerDecision`. When no status change is needed (another reviewer is still
blocking), the fix adds zero overhead.

---

## Implementation Priority

| Priority | Item | Expected gain | Effort |
|---|---|---|---|
| 1 | P1 — Cache `getRepositoryRootCommit()` | ~50 ms per stream write | Low |
| 2 | S1 — Parallelize comment stream writes | ~200 ms per comment | Low |
| 3 | S2 — Batch comment stream push per comment | 2 fewer git pushes per comment | Medium |
| 4 | P2 — Batch ref OID resolution (`for-each-ref`) | ~100–300 ms per metadata save | Low |
| 5 | V1 — Skip unchanged streams on resolve | 2 fewer pushes per resolve | Low |
| 6 | P3 — Batch conflict-reset fetch | ~200–400 ms per retry | Low |
| 7 | R1 — Merge reviewer status into metadata batch | Eliminates duplicate push | Medium |
| 8 | CL1 — Skip redundant snapshot on close | Eliminates snapshot round-trip on retry | Low |
| 9 | C3 — Skip normalization on create | Minor I/O saving | Low |
| 10 | S4 — Semaphore on `saveAllComments` concurrency | Stability under load | Low |
| — | W1 — Re-review status sync (correctness fix) | **Already implemented** | — |

---

## Measuring Progress

All write operations already emit `TIMING [reviewId]` log entries at `INFO` level.
Before and after each change:

1. Enable `INFO` logging for `GitReviewNotesManager`, `ReviewCommentManager`, and
   `ReviewerManager`.
2. Perform the target operation against a real remote repository.
3. Grep the log output for `TIMING` lines and sum the measured durations.
4. Compare totals before and after each fix.

Key metrics to track per operation:

| Operation | Target end-to-end time |
|---|---|
| Create review (single repo) | < 3 s |
| Edit review metadata | < 2 s |
| Reviewer status change | < 1 s |
| Save single comment | < 1 s |
| Resolve comment | < 500 ms |



