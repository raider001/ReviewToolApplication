# Using Comment Indexing — Roadmap (Technical Spike 3, Client Side)

## Status Summary

- ✅ **M0** — `CommentIndexerClient`: query indexer for comment routing
- ✅ **M1** — Multi-repository comment load on review open
- ✅ **M2** — SSE: Handle `comment.added` / `comment.updated` events
- ✅ **M3** — Cleanup & Tests

---

## Key Architecture Documents

| Document | Location | Relevant To |
|---|---|---|
| **Data Structure Design** | [`ServerlessReviewTool/Documentation/Design/datastructure.md`](../../../Documentation/Design/datastructure.md) | All milestones — comment storage format (metadata/text/status per `comment_id`) |
| **Comment Design** | [`ServerlessReviewTool/Documentation/Design/comments.md`](../../../Documentation/Design/comments.md) | All milestones — write/read paths, retry semantics |
| **Client Interface Specification** | [`ServerlessReviewTool/Documentation/Design/Interfaces/Client-Interface.md`](../../../Documentation/Design/Interfaces/Client-Interface.md) | M0, M2 — endpoint shape, SSE event payloads |
| **Indexer Roadmap (Spike 3)** | [`ReviewToolCentralIndexer/Documentation/Design/RoadMaps/Technical Spike 3/comments-indexing-roadmap.md`](../../../ReviewToolCentralIndexer/Documentation/Design/RoadMaps/Technical%20Spike%203/comments-indexing-roadmap.md) | Dependency — M0 and M2 require indexer M3 and M4 to be live |

---

## Purpose

The comment write/read storage is already implemented via `OrphanBranchReviewManager` (three sub-streams per comment: `metadata`, `text`, `status` at `reviews/{reviewId}/comments/{commentId}/`). What is missing is:

1. **Indexer routing** — `ReviewCommentManager` currently loads comments from a hardcoded primary repository only. It needs to call `GET /reviews/{reviewId}/comments` on the indexer to discover which repositories have comment activity and which `comment_id`s to load.
2. **Multi-repository load** — Load comments from multiple repositories in parallel using the routing list from the indexer.
3. **Live SSE updates** — Handle `comment.added` and `comment.updated` events to refresh the comment panel without a full reload.

---

## Milestone 0 — `CommentIndexerClient`: Query Indexer for Comment Routing

**Goal:** Implement an HTTP client that calls `GET /reviews/{reviewId}/comments` on the indexer and returns the list of `{repository_url, comment_id}` routing pairs. This decouples comment loading from any hardcoded repository assumption.

### Architecture References
- [`Client-Interface.md`](../../../Documentation/Design/Interfaces/Client-Interface.md) — endpoint contract: returns `[{repository_url, comment_id, last_updated}]`; 404 = no comments yet

**Requires:** Indexer M3 must be live.

### What Must Be Added

| Class | Responsibility |
|---|---|
| `CommentIndexerClient` | `GET /reviews/{reviewId}/comments` → list of `{repositoryUrl, commentId, lastUpdated}`; returns empty list on 404 |

**Response model:**
```java
record CommentRoutingEntry(String repositoryUrl, String commentId, String lastUpdated) {}
```

- 200 → deserialise array; return list
- 404 → return empty list (review exists but has no comments yet)
- 401 → throw auth exception
- Other error → log and return empty list

Auth token forwarded in `Authorization: Bearer` header.

### Deliverables

- ⬜ `CommentIndexerClient` class
- ⬜ Wired into `ReviewCommentManager` (replacing or extending the existing primary-repo assumption)

### Acceptance Criteria

- Unit: 200 response → correct list of `CommentRoutingEntry`
- Unit: 404 response → empty list; no exception
- Unit: auth token forwarded in request header
- Integration: call against live indexer returns entries written by M2 upsert

### Test Types

- Unit: mock HTTP; verify deserialisation and status-code handling
- Integration: `CommentIndexerClient` over TCP against seeded test DB

---

## Milestone 1 — Multi-Repository Comment Load on Review Open

**Goal:** Extend `ReviewCommentManager` so that on initial review open it loads comments from all repositories reported by the indexer, in parallel, rather than from a single hardcoded repository.

### Architecture References
- [`Comment Design`](../../../Documentation/Design/comments.md) — read path: list `comment_id`s from indexer → read three sub-streams per comment from the correct repository clone

**Requires:** Indexer M3 live; M0 of this roadmap complete.

### What Must Change

**`ReviewCommentManager`:**

Replace `loadCommentsFromKnownRepository(reviewId, primaryRepoName)` with a new path that:

1. Calls `CommentIndexerClient.getCommentRouting(reviewId)` → `List<CommentRoutingEntry>`
2. Groups entries by `repositoryUrl`
3. For each repository in parallel: reads the three sub-streams (`metadata`, `text`, `status`) for each `commentId` in that repository via the existing `OrphanBranchReviewManager.readCommentMetadata/Text/Status` calls
4. Merges results across all repositories; returns unified `List<ReviewComment>`

The per-comment read logic inside `loadSingleComment` is already correct — this milestone only changes how the initial set of `(repositoryUrl, commentId)` pairs is obtained.

### Deliverables

- ⬜ `ReviewCommentManager.loadAllComments(reviewId)` — uses indexer routing; parallel across repositories
- ⬜ `loadCommentsFromKnownRepository` retained or deprecated (used as fallback if indexer unavailable)

### Acceptance Criteria

- Review with comments in 2 repositories: both sets loaded, merged into one list
- Review with no comments: indexer returns 404 → empty list; no crash
- Review with comments in one repo: behaves identically to existing single-repo load
- Auth token forwarded to indexer call

### Test Types

- Unit: two `CommentRoutingEntry` sets from different repositories → parallel load → merged result
- Integration: two clone fixtures seeded with different `comment_id`s; assert merged load

---

## Milestone 2 — SSE: Handle `comment.added` / `comment.updated`

**Goal:** Wire the new SSE event types into the client SSE handler so that incoming `comment.added` or `comment.updated` events trigger a targeted comment reload and panel refresh.

### Architecture References
- [`Client-Interface.md`](../../../Documentation/Design/Interfaces/Client-Interface.md) — `comment.added` / `comment.updated` payloads: `{type, review_id, repository_url, comment_id}`

**Requires:** Indexer M4 (SSE event publishing) must be live.

### What Must Change

**SSE event handler** (wherever `review.updated`, `branch.updated` are currently handled):

| SSE event | Client action |
|---|---|
| `comment.added` | Re-read `metadata`, `text`, and `status` sub-streams for `comment_id` from `repository_url` clone; insert or update that comment in the panel |
| `comment.updated` | Re-read `status` sub-stream for `comment_id` from `repository_url` clone; update resolution state in the panel |

The SSE payload carries `repository_url` and `comment_id` directly — no indexer call needed on SSE; the client reads from the clone identified in the payload.

**`ReviewCommentManager`:**

Add `reloadComment(reviewId, repositoryUrl, commentId)` — reads all three sub-streams for the given `commentId` from the named repository's clone and returns the updated `ReviewComment`.

### Deliverables

- ⬜ `comment.added` SSE event wired to comment reload + panel insert/update
- ⬜ `comment.updated` SSE event wired to status re-read + panel refresh
- ⬜ `ReviewCommentManager.reloadComment(reviewId, repositoryUrl, commentId)`

### Acceptance Criteria

- Receiving `comment.added` for a new `comment_id` → new comment appears in panel within 2 seconds (end-to-end: git push → SSE → UI)
- Receiving `comment.updated` for an existing `comment_id` → resolved state updates in panel
- SSE for a review not currently open → no action (guard by active review ID)
- Receiving both `comment.added` and `comment.updated` for the same `comment_id` in the same flush window → deduplicate to one reload

### Test Types

- Integration: simulate SSE frame arrival → assert reload triggered and panel state updated

---

## Milestone 3 — Cleanup & Tests

**Goal:** Remove the now-unused primary-repo-only comment load path and add end-to-end tests covering the full write → index → SSE → read cycle.

### What Must Be Removed or Cleaned Up

- `loadCommentsFromKnownRepository` if deprecated in M1 — remove callers and the method itself
- Any hardcoded primary-repository assumption in comment loading outside of `ReviewCommentManager`
- Dead `ReviewCommentManager` constructor parameters that provided a primary repo name if no longer needed

### Scenarios to Exercise

| Scenario | Expected outcome |
|---|---|
| User posts a new comment | `metadata` + `text` sub-streams written; indexer fires `comment.added`; panel shows comment |
| User replies to a comment | `text` sub-stream gains new entry; indexer fires `comment.added`; panel refreshes |
| User resolves a comment | `status` sub-stream gains entry; indexer fires `comment.updated`; resolved badge updates |
| SSE arrives for a review not currently open | No action |
| Indexer returns 404 on open | Comment panel shows empty state; no crash |
| Comment in secondary repository | Loaded via indexer routing; appears in panel alongside primary-repo comments |

### Deliverables

- ⬜ Dead code removed
- ⬜ `CommentRoundTripIT` — writes comment via `ReviewCommentManager`, reads back via `loadAllComments`, asserts graph
- ⬜ `CommentSseIT` — SSE frame arrival triggers panel refresh end-to-end

### Test Types

- Integration: `OrphanBranchStore` + blobless clone fixture; write → index → read round-trip
- Integration: SSE frame → panel refresh

---

## Roadmap Timing Summary

| Milestone | Estimated effort | Indexer dependency |
|---|---|---|
| M0 — Indexer Client | 0.5 days | Indexer M3 live |
| M1 — Multi-repo Load | 1 day | Indexer M3 live |
| M2 — SSE Handling | 1 day | Indexer M4 live |
| M3 — Cleanup & Tests | 1 day | All above |
| **Total** | **~3.5 days** | |

---

## Summary: What Changes

### Classes

| Class | Action |
|---|---|
| `CommentIndexerClient` | New — `GET /reviews/{reviewId}/comments`; returns `List<CommentRoutingEntry>`; empty on 404 |
| `ReviewCommentManager` | Extend — `loadAllComments` (indexer routing + parallel multi-repo reads); `reloadComment` (SSE-triggered targeted read) |
| SSE event handler | Extend — handle `comment.added` and `comment.updated` with `comment_id` payload |

### Removed

| Item | Reason |
|---|---|
| `loadCommentsFromKnownRepository` (primary-repo only) | Replaced by `loadAllComments` which uses indexer routing |
| Hardcoded primary-repo comment load paths | All comment discovery now routes through `CommentIndexerClient` |
