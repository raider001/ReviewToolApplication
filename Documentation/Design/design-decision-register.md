# Design Decision Register

## Review Database
Captures all design decisions and why for the project.
### Decision Use 
- git notes as database

### Why
- **Purpose**: Centralized repository for tracking and documenting design decisions made during the development process.
- **Format**: Markdown files organized by decision category (e.g., architecture, data structure, user interface).
- **Version Control**: Each decision is versioned, allowing for tracking changes and historical context.
- **Access Control**: Controlled access to ensure only authorized personnel can modify or add new decisions.
- **Documentation**: Includes rationale, alternatives considered, and impact analysis for each decision.
- **Infrastructure Agnostic**: Only requirement is git. Will work with any service (Bitbucket, GitHub, GitLab) that supports git and git notes. No reliance on external databases or services.
- **Security**: By using git notes, we can leverage existing git security and access control mechanisms to protect the integrity of the design decisions.

### Acknowledgements
- Won't have immediate support for automated updates on clients. (We will require webhooks or similar to notify users of changes)

## Data Update Method
- **File based**: Updates and changes will be stored in individual files in the repository to prevent merge conflicts.

### Why
- **Auditing**: Each change is recorded and auditable for transparency and traceability.
- **Conflict Avoidance**: By using individual files for each decision, we can avoid merge conflicts that arise from multiple contributors editing the same file.

### Acknowledgements
- Can become file heavy in the system.
  - Mitigation: We can implement a cleanup strategy to archive old decisions or use a structured directory system to manage files effectively.

## Review Discovery

## Git Notes Anchor Strategy

### Decision
All review metadata notes are attached to the repository's **root commit** (the first commit with no parents).

### Why
Git notes must be attached to a specific commit. We evaluated several options:

**Options Considered**:

1. **Attach to HEAD** ❌
   - Problem: HEAD moves when branches are merged or switched
   - Result: Notes become inaccessible when HEAD changes
   - Example: After merge, HEAD points to new commit, but notes are on old commit

2. **Attach to first commit in review** ❌
   - Problem: If commits are rebased/rewritten, their hashes change
   - Result: Notes lost when commit history is rewritten
   - Example: Interactive rebase changes commit SHA, orphaning notes

3. **Create empty anchor commits** ❌
   - Problem: Creates unnecessary commits in repository
   - Problem: Anchor commits may not be fetched if not reachable from branches
   - Problem: Risk of garbage collection for unreachable commits
   - Problem: Need to track and store anchor commit hashes

4. **Use repository root commit** ✅ **CHOSEN**
   - **Always exists**: Every Git repository has an initial commit
   - **Never changes**: Root commit hash is immutable forever
   - **Always fetchable**: Part of every branch's history
   - **Easy to discover**: Simple command: `git rev-list --max-parents=0 HEAD`
   - **No extra commits**: Uses existing infrastructure
   - **Conceptually clean**: Review data is separate from code commits

### Implementation
```java
private CompletableFuture<String> getRepositoryRootCommit() {
    return git.executeAsync(
        repositoryName,
        "rev-list", "--max-parents=0", "HEAD"
    ).thenApply(output -> {
        String[] commits = output.trim().split("\n");
        return commits[0].trim();
    });
}
```

### Benefits
- **Decoupled from code**: Review metadata independent of code commit lifecycle
- **Discoverable**: Reviews found via `git for-each-ref refs/notes/reviews/`
- **Durable**: Survives merges, rebases, cherry-picks, branch deletions
- **Simple**: No tracking of anchor commits, no extra infrastructure
- **Distributed**: Syncs automatically via standard Git push/fetch

### Implications
- All reviews in a repository share the same anchor commit (the root)
- When reading/writing review notes, system first finds root commit
- Root commit is queried once per operation and can be cached
- Works correctly even if repository has multiple root commits (rare): uses first one

### Storage Structure
```
Repository commits:    [root] → A → B → C → ... → HEAD
                         ↑
                         └── All review metadata notes attached here
                         
refs/notes/reviews/{review-id}/metadata/title  → note on [root]
refs/notes/reviews/{review-id}/metadata/status → note on [root]
refs/notes/reviews/{review-id}/reviewers       → note on [root]
```

### Edge Cases
- **Multiple root commits**: Use first one returned by `rev-list --max-parents=0 HEAD`
- **Empty repository**: Cannot create reviews (no commits exist yet)
- **Shallow clone**: Root commit included in fetch, no special handling needed

### Date Decided
2026-05-03

### Status
**ACTIVE** - Implemented in GitReviewNotesManager.java


## Primary Repository Ownership

### Decision
Each review has a designated **primary repository** that owns and stores the authoritative review data. The primary repository is automatically set at review creation time and stored in the review metadata.

### Why
**Problem**: Without a primary repository concept, the same review could be stored in multiple repositories, leading to:
- Data duplication across repositories
- Conflicts when merging reviews from multiple sources
- Unclear ownership and responsibility for review data
- Potential consistency issues between repositories

**Solution**: Introduce `primaryRepository` metadata field that:
- Is set automatically when the review is created (using the repository where `createReview` was called)
- Is stored as an append-only NDJSON stream like other metadata
- Identifies which repository owns the definitive review data
- Resolves conflicts when the same `reviewId` appears in multiple repositories

### Implementation

**Data Model Changes**:
```java
public class ReviewItem {
    private final String primaryRepository;
    // ...other fields
}
```

**Storage Structure**:
```
refs/notes/reviews/{review-id}/metadata/primaryRepository
```

**Creation Flow**:
1. User creates review via `GitReviewNotesManager.createReview()` in a specific repository
2. System automatically writes `primaryRepository` field with the repository name
3. This field should remain immutable (though technically it's an append-only stream)

**Loading & Merging**:
- When `ReviewItemManager` loads reviews from multiple repositories
- If same `reviewId` found in multiple repos, the `primaryRepository` field determines the owner
- Conflict resolution: If multiple repos claim to be primary, use the first one encountered and log a warning

### Benefits
- **Clear Ownership**: Each review has one authoritative source
- **Prevents Duplication**: Only primary repository stores full review data
- **Conflict Resolution**: Unambiguous resolution when same review ID appears in multiple places
- **Data Integrity**: Reduces risk of inconsistent review states across repositories
- **Future-Proof**: Enables advanced features like cross-repository review references

### Trade-offs
- **Added Complexity**: One more field to manage in review lifecycle
- **Migration**: Existing reviews without this field default to first repository in their list
- **Not Truly Immutable**: Technically can be changed via append (but shouldn't be)

### Edge Cases
- **Missing Primary Field**: Defaults to the repository where review was first discovered
- **Conflicting Primary Claims**: First one wins, warning logged
- **Deleted Primary Repo**: Review continues to function; primaryRepository field is just metadata

### Date Decided
2026-05-04

### Status
**ACTIVE** - Implemented across ReviewItem, GitReviewNotesManager, ReviewItemLoader, ReviewItemManager


## User Identity Trust Model

### Decision
Use Git-provided identity (`git config user.name`) as the tool's user source. Do not add application-level authentication (validation only).

### Acknowledgement
Because this tool relies entirely on Git, it cannot validate a person's real identity or personal information when reviews are pushed. Git handles access/authentication through the remote service; the tool only sees the identity the user claims locally.

### Implication
- The tool can record and display user information
- The tool cannot prove that name is real
- Preventing spoofing requires Git-level signing or external identity services, not UI checks

### Extensibility
The tool will support plugging in third-party services for user validation and basic profile enrichment. This validation applies only inside the tool UI/workflow and does not authenticate Git pushes at the remote service level.

### Boundary
- Tool-level validation can guide or block actions in the UI
- It cannot enforce identity for direct/manual Git pushes outside the tool

### Status
**ACTIVE** - Accepted limitation


## External Indexer and Notification Plugin for Scale

### Decision
The Central Indexer is the permanent, queryable review index and the sole source of live change notifications for connected clients. Git (notes or orphan branch — see separate proposal) remains the authoritative store for full review detail. The client never polls git for the review list; it receives all review list changes from the indexer via SSE.

### Why
At higher review counts, full git scanning on every client becomes the primary bottleneck. More fundamentally, with the indexer acting as the webhook receiver for all provider events, it is already the first component to know when anything changes. The client has no reason to re-derive that information itself.

The indexer solves this by:

- maintaining a **permanent** `reviews` table — one row per review, storing summary data (ID, title, author, status, branch, base branch, primary repository, reviewers, last updated)
- maintaining the existing `events` and `repository_state` tables for the event log and reconciliation window
- serving the full review list to any client on `GET /reviews` at connect time
- pushing incremental `review.updated`, `branch.created`, `branch.deleted`, `commit.pushed` events to connected clients via SSE
- using PostgreSQL `pg_notify` → `LISTEN` to fan out events from the writer thread to all SSE connections without polling

### Infrastructure

The indexer already runs PostgreSQL with:
- `events` table — append-only event log with per-repository sequence numbers and delivery-id deduplication
- `repository_state` table — tracks `last_sequence_no` and `last_event_time` per repository for reconciliation

The `reviews` table is the new permanent addition:

```sql
CREATE TABLE IF NOT EXISTS reviews (
    review_id    TEXT        PRIMARY KEY,
    status       TEXT        NOT NULL DEFAULT 'OPEN',
    repositories JSONB       NOT NULL DEFAULT '[]',
    last_updated TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Updated or inserted on every `review.updated` event received by the indexer. Queried on client connect filtered by `since=`, `status=`, and `repositories=`. All review content (title, author, branch, reviewers, comments) is fetched from git on demand via `git cat-file --batch` — the indexer stores routing and filter keys only.

### Scope Boundary

- **Git responsibility**:
  - authoritative store for full review detail (comments, reviewer history, file data, audit trail)
  - write target for all create/update operations from the client
  - conflict resolution and recovery source

- **Indexer responsibility**:
  - permanent review summary index (`reviews` table)
  - branch and commit state per repository
  - event log with retention window
  - SSE fan-out to all connected clients
  - webhook ingestion from all providers
  - reconciliation on startup and reconnect

- **Client responsibility**:
  - write review data to git
  - read full review detail from git on demand (single review selected by user)
  - subscribe to indexer SSE for all list-level updates
  - cache last-known review list locally for offline / indexer-down fallback

### Client Connect Sequence

```
Client connects to indexer
  → GET /reviews?since=<timestamp>&status=OPEN
     → Fetch review index including `repositories` and `branches` (repository entries include `repository_url`)
  → GET /branches?q=<branch_prefix>&include_reviews=true   fetch global branch state / resolve branch→repository and optional review routing keys
  → Subscribe SSE                   receive incremental updates from this point forward

User selects a review
  → Read full metadata from git      targeted, on demand — not from indexer

User creates / edits a review
  → Write to git
  → Indexer receives webhook (or POST /events/notify)
  → Indexer updates reviews table
  → Indexer emits SSE event to all connected clients
```

### Fallback When Indexer Is Unavailable

- Client displays last locally cached review list with a visible staleness warning
- Write operations (create/edit review) proceed against git directly — they do not require the indexer
- On reconnect, client re-fetches `GET /reviews` to reconcile any missed updates

### Benefits
- **Scalability**: `O(1)` review list fetch at client connect regardless of review count
- **Responsiveness**: Near real-time incremental updates via SSE — no polling anywhere
- **Separation of Concerns**: Index decoupled from full-detail storage; each optimised for its access pattern
- **No client polling**: Clients are purely reactive to indexer events for list-level state

### Trade-offs
- **Indexer is now required for normal operation**: The review list is not available without the indexer unless the client falls back to its local cache
- **Consistency window**: The `reviews` table may lag git by the time it takes the webhook to arrive and be processed — typically sub-second on normal network conditions
- **Bootstrap cost**: First-run scan across all tracked repositories to populate the `reviews` table; one-time operation, resumable

### Date Decided
2026-05-20

### Status
**REVISED** — Expanded from accelerator-only to permanent index store. Supersedes the 2026-05-11 entry. Pending implementation of `reviews` table, `GET /reviews` endpoint, and client SSE subscription logic.
