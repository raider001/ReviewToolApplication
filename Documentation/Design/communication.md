# Communication Design

## Objectives

The tool must be notified when any of the following events occur in a tracked repository:

1. **Branch created** — a new branch has appeared on the remote.
2. **Branch deleted** — an existing branch has been removed from the remote.
3. **Commit pushed** — new commits have been pushed to a branch.
4. **Notes pushed** — a git notes ref (`refs/notes/*`) has been pushed to the remote.

These four events are the complete set of signals required to keep the tool's review list and branch state current.

---

## Event Sources

### Branch Created / Branch Deleted / Commit Pushed

Git hosting providers (GitHub, Bitbucket) fire push webhook events for `refs/heads/*`. These three objectives are therefore fully covered by standard webhooks in real time.

### Notes Pushed

Git hosting providers **do not** fire webhook events for pushes to `refs/notes/*`. This is a hard limitation of every major provider.

| Provider         | `refs/heads/*` webhook | `refs/notes/*` webhook |
|---|---|---|
| GitHub           | ✅ | ❌ |
| Bitbucket Cloud  | ✅ | ❌ |
| Bitbucket Data Center | ✅ | ❌ |
| GitLab           | ✅ | ❌ |

Notes pushes therefore require a different notification mechanism.

---

## Candidate Solutions for Notes Notification

### 1. Client-Initiated Notification
After a successful notes push, the tool calls `POST /events/notify` directly on the Central Indexer. Because the tool is the only actor that ever pushes notes, it is perfectly positioned to be the notification source. However, if the indexer is offline at the time of the push the notification is silently lost — the client has no way to queue or retry it durably. This gap must be covered by a catch-up mechanism.





---

## Catch-Up: Reconciliation

Reconciliation covers events the indexer missed due to a restart or offline period.

- Runs on indexer startup
- Queries the provider API for changes since `last_event_time` per repository
- Generates the same events as live paths through the same pipeline
- Bounded by `indexer.retentionDays` — events outside the window are unrecoverable

### Known Gap: Network Outage (Client or Server Side)

Reconciliation only triggers when the **indexer restarts**. If either the client or the indexer loses network connectivity — without a full process restart — any notes pushed during that window are permanently lost. The indexer has no knowledge a push occurred and no trigger to go looking for it.

### Resolution: Reconnect-Triggered Reconciliation with Persistent Pending State

When the client's SSE connection to the indexer reconnects after a drop, it replays any unacknowledged notifications. To support this:

- The client maintains a **persistent queue** of notifications that have not yet been acknowledged by the indexer. This queue survives client restarts.
- Until a notification is acknowledged, the UI surfaces a visible warning to the user that the change has been pushed to git but **other users have not yet been notified**.
- On SSE reconnect, the client flushes the pending queue to the indexer before resuming normal operation.
- Once the indexer acknowledges receipt, the pending state is cleared and the warning is removed.

This approach makes the failure visible and recoverable rather than silent and permanent. Blocking a user from pushing notes entirely when the indexer is offline is considered too restrictive — the git data is safe, only the notification is delayed.

---

## Decision

| Event | Mechanism |
|---|---|
| Branch created | Provider webhook → Central Indexer |
| Branch deleted | Provider webhook → Central Indexer |
| Commit pushed | Provider webhook → Central Indexer |
| Notes pushed (review updated) | `post-receive` hook → Central Indexer (self-hosted) / Client → `POST /events/notify` → Central Indexer (cloud) |

Webhooks handle all branch and commit events as they are natively supported by every provider.

For notes, the preferred solution depends on deployment type:

- **Self-hosted** (GitLab self-managed, Bitbucket Data Center) — a `post-receive` server-side hook fires reliably for all `refs/notes/*` pushes regardless of which client or tool made the push. This is the most robust solution as it operates at the git server level and is not dependent on any individual client being online or configured correctly.
- **Cloud-hosted** (GitHub, GitLab.com, Bitbucket Cloud) — server-side hooks are unavailable. Client-initiated notification (`POST /events/notify`) is the only viable path. The client persists unacknowledged notifications locally and replays them on reconnect, surfacing a visible warning to the user until the indexer acknowledges receipt.

In all cases the Central Indexer persists the event and fans it out to connected clients via SSE.

---

## Status

**AGREED** — Awaiting implementation of `POST /events/notify` endpoint, client-side persistent pending queue, and `post-receive` hook documentation for self-hosted deployments.



