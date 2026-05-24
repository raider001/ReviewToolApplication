# Notes-to-Branch Pivot Proposal

## Problem Statement

The current git notes storage model has a fundamental communication gap: no major git provider fires webhooks for pushes to `refs/notes/*`. Notification requires either a client-initiated `POST /events/notify` call (cloud) or a `post-receive` server hook (self-hosted) — neither is universal or fully reliable.

---

## Proposed Change

Replace git notes with a dedicated orphan branch (`refs/heads/kalynx-reviews`) per repository. Reviews are stored as individual files on that branch using the same directory structure currently used for notes.

---

## Relationship to the Central Indexer

The Central Indexer already runs **PostgreSQL** and maintains:
- `events` — append-only event log with per-repository sequence numbers and `pg_notify`-driven SSE fan-out
- `repository_state` — last sequence number and last event time per repository for reconciliation

Under this proposal the indexer gains one additional permanent table:

```sql
CREATE TABLE IF NOT EXISTS reviews (
    review_id    TEXT        PRIMARY KEY,
    status       TEXT        NOT NULL DEFAULT 'OPEN',
    repositories JSONB       NOT NULL DEFAULT '[]',
    last_updated TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

When the indexer receives a push webhook for `refs/heads/kalynx-reviews` it:

1. Reads the changed review file(s) from the orphan branch via sparse fetch (`--filter=blob:none --depth=1`)
2. Upserts the affected row(s) in the `reviews` table
3. Emits a `review.updated` event via `pg_notify` → SSE to all connected clients

Clients receive the full review list on `GET /reviews` at connect time and receive incremental updates via SSE thereafter. The client never polls git for the review list.

---

## Why

### Webhooks Fire Natively

A push to `refs/heads/kalynx-reviews` is a standard branch push. Every provider fires a push webhook for it.

| Event | Current (git notes) | Orphan branch |
|---|---|---|
| Branch created | Provider webhook ✅ | Provider webhook ✅ |
| Branch deleted | Provider webhook ✅ | Provider webhook ✅ |
| Commit pushed | Provider webhook ✅ | Provider webhook ✅ |
| Review pushed | Client POST / post-receive ⚠️ | Provider webhook ✅ |

The entire notes notification problem — client-initiated notify, persistent pending queues, post-receive hooks, reconnect reconciliation — disappears. Objective 4 from `communication.md` becomes identical to Objective 3.

### Storage Model Simplification

Reviews no longer need to be append-only NDJSON streams. Current state per file is sufficient; git history provides the audit trail. This significantly simplifies reads — no stream replay required, just read the file.

---

## Scale: Can We Avoid Cloning Entirely?

A `kalynx-reviews` branch accumulates all reviews for a repository over time. Full clone or pull before every write is not viable at scale.

**Yes — the tool never clones or checks out the reviews branch.** All reads and writes use git plumbing commands against a local bare object store.

### Write Path

| Step | Mechanism | What Transfers |
|---|---|---|
| Initialise (once per session) | `git init --bare` | Nothing |
| Fetch branch tip | `git fetch --filter=blob:none --depth=1 origin refs/heads/kalynx-reviews` | Tree + commit objects only — no file contents, no history |
| Read existing file (if needed) | `git cat-file blob <hash>` | One blob, fetched on demand |
| Write new content | `git hash-object -w` | Writes one blob locally |
| Reconstruct affected trees | `git mktree` (root → subfolder chain only) | One tree object per ancestor directory |
| Create commit | `git commit-tree <tree> -p <parent>` | One commit object |
| Push | `git push origin <commit>:refs/heads/kalynx-reviews` | Delta-compressed; only new objects sent |

`--filter=blob:none` instructs the server to send tree and commit objects only during fetch. Individual blob objects are requested lazily via `cat-file`. A write operation that touches one review file transfers exactly: the ancestor tree chain + one blob + one new commit — completely independent of how many other reviews exist on the branch.

**A branch with 50,000 review files costs the same to write as a branch with 10.** The transfer size is bounded by the directory depth of the path being written, not the total number of files on the branch.

### JGit Alignment

Since this is a Java application, JGit supports all of this natively:

- `ObjectInserter` — write blobs, trees, and commits directly into the object store
- `TreeWalk.forPath()` — resolve a single path in a tree without walking anything else
- `Transport` with partial clone filter — fetch with `--filter=blob:none`
- `RefUpdate` — push the new commit ref

No `CheckoutCommand`, no `WorkingTreeIterator`, no `CloneCommand`.

---

## Trade-offs

### Conflict Handling Required

Two clients writing to the same review file simultaneously produce divergent commits. The second push will be rejected (non-fast-forward). The tool must handle this:

1. Detect push rejection
2. Fetch the updated tip
3. Re-apply the change on top — no three-way merge needed for independent files; just reconstruct the tree at the new tip with your blob inserted
4. Retry the push

This replaces the current conflict-free append-only model with a retry-on-conflict model. The conflict window is narrow (only writes to the exact same file at the exact same time conflict), but the handling logic and UI surface must be designed for it.

### Migration

Reviews currently stored in git notes would need to be migrated to the orphan branch on first run per repository.

### Removes

- Client-initiated `POST /events/notify` endpoint
- Persistent pending queue for unacknowledged notifications
- `post-receive` hook requirement for self-hosted deployments
- Append-only NDJSON stream format
- Root commit anchor strategy (no longer needed — branch is the anchor)

---

## Date Raised

2026-05-20

## Status

**UNDER CONSIDERATION** — Pending decision.


