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
Keep Git notes as the authoritative review store, and introduce an external indexer/custom plugin service for large-scale discovery, query, and change notification workflows.

### Why
At higher review counts, full Git-note scanning and repeated metadata hydration become the primary bottleneck. The indexer solves this by:

- maintaining a materialized review routing/summary index (`reviewId` to primary repository and summary metadata)
- serving paged/filterable queries without repository-wide scans
- emitting changed review IDs so clients refresh incrementally
- reducing plugin-side work to targeted hydration of only relevant reviews

This keeps the tool aligned with Git-first storage while moving scale-heavy query operations to a dedicated component.

### Scope Boundary
- **In-tool responsibility**:
  - write and read authoritative review data in Git notes
  - operate when the indexer is unavailable (fallback behavior)
  - hydrate full review details for selected review IDs
- **Indexer/plugin responsibility**:
  - repository-wide indexing and summary query
  - review ID routing lookup
  - change-feed notifications to connected clients

### Benefits
- **Scalability**: Avoids `O(total reviews)` refresh paths in clients
- **Responsiveness**: Enables near real-time incremental updates in UI lists
- **Separation of Concerns**: Keeps source-of-truth storage decoupled from query acceleration
- **Extensibility**: Allows alternative index implementations without changing review storage format

### Trade-offs
- **Operational Complexity**: Adds another deployable component
- **Consistency Window**: Index may lag behind Git briefly (eventual consistency)
- **Fallback Requirement**: Client must handle index downtime gracefully

### Operational Rules
- Indexer is an accelerator, not the source of truth
- Git notes remain canonical for conflict resolution and recovery
- Client must support direct-by-`reviewId` fallback reads from Git

### Date Decided
2026-05-11

### Status
**ACTIVE** - Required direction for 100k+ review scaling strategy



