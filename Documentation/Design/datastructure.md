
# Data Structure Design

## Overview

This document defines the data structures used in the Serverless Code Review Tool. All data is persisted as JSON within Git Notes, ensuring the system remains truly serverless with Git as the single source of truth.

---

## Core Principles

- **Git Notes as Storage**: All review data stored in Git notes under dedicated refs namespaces
- **JSON Serialization**: All structures serialized as JSON for portability and inspectability
- **Immutable Commits**: Reviews reference specific commit SHAs but can span multiple commits
- **Distributed Sync**: Data synchronized via Git push/fetch operations
- **Trust-based Model**: No cryptographic signatures; relies on Git remote access control
- **Multi-Commit Reviews**: A review can encompass multiple commits (commit range or set)

---

## Key Design Decisions

### Reviews Span Multiple Commits

**Requirement**: A review is not attached to a single commit but can encompass many commits.

**Implications**:
- Need unique review identifier (separate from commit SHA)
- Must identify review boundaries (start commit, end commit, or commit list)
- Storage strategy cannot simply use commit SHA as the key
- Review represents a logical unit of changes across multiple commits

**Questions to Answer**:
1. How do we uniquely identify a review? (UUID, sequential ID, derived from commits?)
2. How do we define review boundaries?
   - Start commit + end commit (range)?
   - Explicit list of commit SHAs?
   - Base commit + head commit (like PR model)?
3. Where do we store the review note?
   - On the tip commit?
   - On a synthetic identifier?
   - In a separate review registry?
4. How do we discover all reviews?
   - Scan all notes?
   - Maintain an index?
   - Use specific ref structure?

---

## Git Notes Organization

### Note Attachment Strategy

**All review notes are attached to the repository's root commit** (the first commit with no parents).

**Rationale**:
- **Stable Reference**: Root commit hash never changes, even after merges, rebases, or branch operations
- **Always Exists**: Every repository has an initial commit
- **Anchor Commit is Fetchable**: Root commit is part of branch history, so the note target object always exists locally
- **Decoupled from Code**: Review metadata independent of code commit lifecycle
- **Simple Discovery**: Easy to find via `git rev-list --max-parents=0 HEAD`

**Operational Note**:
- Notes refs under `refs/notes/reviews/*` still require explicit fetch configuration (or direct fetch commands).

**Example**:
```bash
# Find root commit (anchor for all notes)
$ git rev-list --max-parents=0 HEAD
735ad14ad0cf3348987da9edc324a128339a9396

# All review notes attached to this commit
$ git notes --ref=refs/notes/reviews/{review-id}/metadata/title show 735ad14...
```

**Storage Pattern**:
```
Repository:  [root: 735ad14] → commit A → commit B → ... → HEAD
                    ↑
                    └─── All review metadata notes attached here
```

### Refs Namespaces

**Append-Only Stream Structure** - Designed for low-conflict concurrent updates with independent lanes per field

```
refs/notes/reviews/{review-id}/
├── metadata/                              # Independent metadata streams (one ref per field)
│   ├── author                             # NDJSON append stream
│   ├── title                              # NDJSON append stream
│   ├── description                        # NDJSON append stream
│   ├── status                             # NDJSON append stream
│   ├── commits                            # NDJSON append stream
│   ├── primaryRepository                  # NDJSON append stream ("true"/"false")
│   ├── branch                             # NDJSON append stream
│   ├── baseBranch                         # NDJSON append stream
│   └── repositoryActive                   # NDJSON append stream (per-repository active flag)
├── reviewers                              # NDJSON append stream
└── comments/                              # Comment threads (one namespace per thread)
    └── {comment-id}/                      # Individual comment thread
        ├── metadata                       # Location (file, line) - written once
        ├── text                           # NDJSON append stream (conversation)
        └── status                         # NDJSON append stream (resolution changes)

```

**Sync Strategy**:
- Fetch review refs with union merge semantics: `git -c notes.mergeStrategy=union fetch ...`
- Push review refs normally after local replay/append operations.

## Data Structures

### ID Generation Strategy

**Format**: UUID v7 (Time-Ordered UUID)

**Rationale**:
- **Globally unique**: 128-bit entropy ensures zero collision probability across distributed clients
- **Time-ordered**: UUIDs created later naturally sort after earlier ones
- **Standard format**: Industry-standard UUID format (8-4-4-4-12 hexadecimal)
- **No coordination required**: Clients generate IDs independently without risk of collision

**Structure**:
- 48 bits: Unix timestamp in milliseconds (time-ordered prefix)
- 4 bits: Version identifier (0111 = version 7)
- 12 bits: Random data
- 2 bits: Variant identifier (10 = RFC 4122)
- 62 bits: Random data (total randomness ensures uniqueness)

**Example ID**: `01890a5d-ac96-774b-bcce-b302099a8057`

**Generation** (Java):
```java
import com.kalynx.serverlessreviewtool.utils.UuidV7Generator;

String id = UuidV7Generator.generate();
```

**Collision Probability**: Effectively zero (2^128 possible values, with 74 bits of randomness per millisecond)

---

### Universal Stream Entry Format

Every appended NDJSON line in a stream uses this structure:
```json
{
  "id": "01890a5d-ac96-774b-bcce-b302099a8057",
  "timestamp": "2026-04-23T13:00:00.456789Z",
  "editor": "john@example.com",
  "data": "<any-data-type>"
}
```

**Field Descriptions**:
- **`id`**: UUID v7 - globally unique identifier for this entry, used for replies/references
- **`timestamp`**: ISO 8601 with microseconds - required for deterministic ordering during replay
- **`editor`**: Email or username - identifies who created this entry
- **`data`**: Any type - field-specific payload (string, object, array, etc.)


## Metadata Notes

---

### Title
**Storage**: `refs/notes/reviews/{uuid}/metadata/title`

**Structure**:
```json
{
  "id": "01890a5d-ac96-774b-bcce-b302099a8057",
  "timestamp": "2026-04-23T13:00:00.456789Z",
  "editor": "john@example.com",
  "data": "Add OAuth2 login flow"
}
```

---

### Description

**Structure**:
```json
{
  "id": "01890a5d-b1a2-774b-bcce-c4f3d89b1234",
  "timestamp": "2026-04-23T13:05:00.123456Z",
  "editor": "john@example.com",
  "data": "Implements OAuth2 with PKCE flow for enhanced security..."
}
```

---

### Status

**Structure**:
```json
{
  "id": "01890a5d-c3e4-774b-bcce-d6a8f12e5678",
  "timestamp": "2026-04-23T13:10:00.123456Z",
  "editor": "john@example.com",
  "data": "pending"
}
```

**Possible Values**:
- `OPEN` - Review opened
- `IN_PROGRESS` - Review being actively reviewed
- `CHANGES_REQUESTED` - Changes requested before approval
- `COMPLETED` - Review completed
- `CANCELLED` - Review cancelled by author

**Compatibility Note**:
- Reader logic normalizes case when parsing; legacy lowercase values are still readable.

---

### Commits
**Storage**: `refs/notes/reviews/{uuid}/metadata/commits`

**Structure**:
```json
{
  "id": "01890a5d-d5f6-774b-bcce-e8b9a23f6789",
  "timestamp": "2026-04-23T13:15:00.123456Z",
  "editor": "john@example.com",
  "data": [
    "abc123def456789...",
    "def456ghi789012...",
    "ghi789jkl012345..."
  ]
}
```

**Lifecycle Semantics**:
- **Active review (open/in-progress)**: UI/diff logic may use `metadata/branch` + `metadata/baseBranch` to compute current diffs.
- **Closed review (completed/cancelled)**: `metadata/commits` becomes the canonical historical snapshot for that repository.
- **Re-open of a closed review**: tooling should use the stored `metadata/commits` list (historical snapshot) instead of recomputing from branch/baseBranch.

**Why this is required**:
- After merge, branch history and base-branch tip may have moved, so branch-vs-base diffs no longer reliably reproduce the original reviewed change set.
- Persisted commit snapshots preserve exactly what was reviewed at closure time.

**Multi-repository behavior**:
- Each repository that participates in a review stores its own `metadata/commits` stream.
- On closed/re-open flows, each repository resolves files/diffs from its local commit snapshot.

### Primary Repository Flag
**Storage**: `refs/notes/reviews/{uuid}/metadata/primaryRepository`

**Structure**:
```json
{
  "id": "01890a5d-e7g8-774b-bcce-f1c2d34e7890",
  "timestamp": "2026-04-23T13:20:00.123456Z",
  "editor": "john@example.com",
  "data": "true"
}
```

### Branch
**Storage**: `refs/notes/reviews/{uuid}/metadata/branch`

**Structure**:
```json
{
  "id": "01890a5d-e7g8-774b-bcce-f1c2d34e7890",
  "timestamp": "2026-04-23T13:20:00.123456Z",
  "editor": "john@example.com",
  "data": "feature/new-login"
}
```

### BaseBranch
**Storage**: `refs/notes/reviews/{uuid}/metadata/baseBranch`

**Structure**:
```json
{
  "id": "01890a5d-e7g8-774b-bcce-f1c2d34e7890",
  "timestamp": "2026-04-23T13:20:00.123456Z",
  "editor": "john@example.com",
  "data": "master-staging"
}
```

---

### Repository Active Flag
**Storage**: `refs/notes/reviews/{uuid}/metadata/repositoryActive`

**Purpose**: Tracks whether each secondary repository is an active participant in the review. Supports soft-delete — a repository can be deactivated without losing any of its stored comment or commit history, and can be reactivated later to bring that history back into context.

**Structure**:
```json
{
  "id": "01890a5d-f9h0-774b-bcce-a2b3c45d6789",
  "timestamp": "2026-04-23T14:00:00.123456Z",
  "editor": "john@example.com",
  "data": {
    "repositoryName": "backend-service",
    "active": false
  }
}
```

**Field Descriptions**:
- **`editor`**: The user who changed the active state
- **`data.repositoryName`**: The repository being flagged
- **`data.active`**: `true` = repository is active in this review; `false` = deactivated (soft-deleted)

**Resolution Rules**:
- **Latest entry per `repositoryName` wins** — multiple entries may exist; only the most recent timestamp matters.
- **No entry = implicitly active** — backwards compatible with all reviews created before this stream existed.
- **Written to the primary repository only** — stored alongside all other review metadata.

**Example** (append entries showing a repository being removed then re-added):
```
refs/notes/reviews/550e8400-.../metadata/repositoryActive
  {"id":"...","timestamp":"2026-04-23T14:00:00Z","editor":"alice@example.com","data":{"repositoryName":"backend-service","active":false}}
  {"id":"...","timestamp":"2026-04-24T09:30:00Z","editor":"bob@example.com","data":{"repositoryName":"backend-service","active":true}}
```


---

---

## Reviewers Notes

**Storage**: `refs/notes/reviews/{uuid}/reviewers`

**Structure**:
```json
{
  "id": "01890a5e-f9h0-774b-bcce-g3d4e56f8901",
  "timestamp": "2026-04-23T16:00:00.234567Z",
  "editor": "jane@example.com",
  "data": {
    "status": "approved",
    "summaryComment": "Looks good overall, minor suggestions in comments"
  }
}
```

**Possible Status Values**:
- `reviewing` - Assigned / currently reviewing
- `approved` - Reviewer approves the changes
- `changes_requested` - Reviewer requests modifications
- `left` - Reviewer removed or left the review

**Compatibility Note**:
- Reader logic also maps legacy values like `pending` and `rejected`.

**Example** (append entries in single reviewer stream):
```
refs/notes/reviews/550e8400-.../reviewers
  {"id":"01890a5e-a1b2-774b-bcce-c3d4e5f67890","timestamp":"2026-04-23T15:00:00.123456Z","editor":"alice@example.com","data":{"status":"pending",...}}
  {"id":"01890a5e-b2c3-774b-bcce-d4e5f6g78901","timestamp":"2026-04-23T16:00:00.234567Z","editor":"alice@example.com","data":{"status":"approved",...}}
  {"id":"01890a5e-c3d4-774b-bcce-e5f6g7h89012","timestamp":"2026-04-23T15:30:00.345678Z","editor":"bob@example.com","data":{"status":"pending",...}}
  {"id":"01890a5e-d4e5-774b-bcce-f6g7h8i90123","timestamp":"2026-04-23T17:15:00.456789Z","editor":"bob@example.com","data":{"status":"changes_requested",...}}
```
---



### Comment Threads

**Purpose**: Comments and discussions on specific parts of the code

**Storage Structure**: Each comment thread has its own namespace with separate streams:

```
refs/notes/reviews/{review-id}/comments/{comment-id}/
├── metadata        # Comment location (file, line) - written once
├── text            # Append-only stream of comment text (original + replies)
└── status          # Append-only stream of resolution status changes
```

#### Metadata Stream
**Storage**: `refs/notes/reviews/{review-id}/comments/{comment-id}/metadata`

**Structure** (single entry, written once):
```json
{
  "id": "01890a5f-g1h2-774b-bcce-h4i5j6k78901",
  "timestamp": "2026-04-23T13:00:00.456789Z",
  "editor": "john@example.com",
  "data": {
    "file": "src/login.py",
    "line": 42,
    "lineEnd": 45,
    "commit": "abc123def456..."
  }
}
```

#### Text Stream
**Storage**: `refs/notes/reviews/{review-id}/comments/{comment-id}/text`

**Structure** (append-only conversation):
```json
{
  "id": "01890a5f-h2i3-774b-bcce-i5j6k7l89012",
  "timestamp": "2026-04-23T13:00:00.456789Z",
  "editor": "john@example.com",
  "data": {
    "text": "Consider adding null check here",
    "replyTo": null,
    "type": "review"
  }
}
{
  "id": "01890a5f-i3j4-774b-bcce-j6k7l8m90123",
  "timestamp": "2026-04-23T14:00:00.456789Z",
  "editor": "jane@example.com",
  "data": {
    "text": "Good catch, will fix",
    "replyTo": "01890a5f-h2i3-774b-bcce-i5j6k7l89012",
    "type": "comment"
  }
}
```

**Type Values**:
- `review` - Comment that needs resolution (code review feedback)
- `comment` - General comment or reply

#### Status Stream
**Storage**: `refs/notes/reviews/{review-id}/comments/{comment-id}/status`

**Structure** (append-only status changes):
```json
{
  "id": "01890a5f-j4k5-774b-bcce-k7l8m9n01234",
  "timestamp": "2026-04-23T15:00:00.456789Z",
  "editor": "john@example.com",
  "data": {
    "needsResolution": true
  }
}
{
  "id": "01890a5f-k5l6-774b-bcce-l8m9n0o12345",
  "timestamp": "2026-04-23T16:00:00.456789Z",
  "editor": "jane@example.com",
  "data": {
    "resolved": true
  }
}
```

**Benefits of Hierarchical Structure**:
- ✅ **True append-only**: Never updates existing entries, only appends
- ✅ **Conversation threads**: Multiple replies in text stream
- ✅ **Status history**: Track who marked resolved/unresolved and when
- ✅ **Low conflicts**: Each comment thread has its own ref namespace
- ✅ **Independent updates**: Location, text, and status can be updated separately

---

## Summary

**Storage Paths**:
- Author: `refs/notes/reviews/{uuid}/metadata/author`
- Title: `refs/notes/reviews/{uuid}/metadata/title`
- Description: `refs/notes/reviews/{uuid}/metadata/description`
- Primary Repository: `refs/notes/reviews/{uuid}/metadata/primaryRepository`
- Branch: `refs/notes/reviews/{uuid}/metadata/branch`
- Base Branch: `refs/notes/reviews/{uuid}/metadata/baseBranch`
- Status: `refs/notes/reviews/{uuid}/metadata/status`
- Commits: `refs/notes/reviews/{uuid}/metadata/commits`
- Repository Active Flag: `refs/notes/reviews/{uuid}/metadata/repositoryActive`
- Reviewers: `refs/notes/reviews/{uuid}/reviewers`
- Comment Metadata: `refs/notes/reviews/{uuid}/comments/{comment-id}/metadata`
- Comment Text: `refs/notes/reviews/{uuid}/comments/{comment-id}/text`
- Comment Status: `refs/notes/reviews/{uuid}/comments/{comment-id}/status`

**Design Philosophy**:
- **Structure IS the data**: The hierarchical path provides context
- **Independent stream per field**: Metadata, reviewer status, and comments are isolated lanes
- **Never update semantics, append entries**: New line entries preserve full history
- **Historical replay over branch drift**: Closed reviews rely on persisted commit snapshots, not moving branch diffs
- **Deterministic replay**: `timestamp` and `id` provide stable ordering and references
- **Editor for accountability**: Every change tracked to source

## References

- [Git Notes Documentation](https://git-scm.com/docs/git-notes)

---




*Document Status: **STABLE** - Core design finalized*
