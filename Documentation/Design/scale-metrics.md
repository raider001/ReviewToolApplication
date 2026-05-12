# Scale Metrics

## Purpose

This document estimates how the current Serverless Review Tool storage and query model scales at:

- 100 reviews
- 1,000 reviews
- 10,000 reviews
- 100,000 reviews
- 1,000,000 reviews

The goal is not to predict exact production numbers, but to provide a realistic design baseline for:

- additional size overhead
- transfer volume and transfer time
- query / refresh time
- processing and memory costs

These estimates are based on the current implementation, which stores review state in Git notes as separate NDJSON streams.

---

## Scope of the Model

This document models the current review representation:

- review metadata stored as NDJSON in Git notes
- one primary repository per review
- optional secondary repository references for multi-repository reviews
- reviewers stored as append-only NDJSON entries
- comment threads stored as separate note streams per thread
- Git notes fetched and queried through Git subprocesses

This is a scale model for the current architecture, not an optimized future design.

### Likelihood note: 1,000,000 reviews in one repository

The 1,000,000-review single-repository case in this document should be treated as an extreme stress scenario, not the expected operating model.

In normal deployments, review volume is more likely to be distributed across many repositories. For example:

- 1,000,000 total reviews across 100 repositories implies ~10,000 reviews per repository
- 1,000,000 total reviews across 250 repositories implies ~4,000 reviews per repository
- 1,000,000 total reviews across 500 repositories implies ~2,000 reviews per repository

This means the single-repository 1,000,000 case is low-likelihood and should primarily be used to understand upper-bound failure modes and architectural limits.

---

## Key Architectural Observations

### 1. Storage is append-only

Most review data is written as `StreamEntry<T>` records:

```text
{"id":"<uuid>","timestamp":"<instant>","editor":"<user>","data":<payload>}
```

This means storage growth is driven by:

- number of streams
- number of entries per stream
- number of repositories per review
- number of comment threads and replies

### 2. The biggest scaling pressure is not raw bytes

Raw storage size is relatively manageable even at very large review counts.

The bigger long-term issues are:

- Git note ref count explosion
- many small note objects
- repeated Git subprocess execution
- linear review scanning in refresh/query flows
- in-memory accumulation of merged review state

### 3. Multi-repository reviews amplify ref count quickly

A single multi-repository review can create:

- 9 primary metadata/reviewer refs
- 3 refs per secondary repository (or more if commit snapshots exist there)
- 2-3 refs per comment thread

So even “small” reviews can generate dozens of note refs.

---

## Baseline Assumptions

The estimates below use a baseline review profile.

### Review-level assumptions

| Assumption | Value | Notes |
|---|---:|---|
| Average repositories per review | 3 | 1 primary + 2 secondary |
| Average title length | 72 chars | Medium descriptive title |
| Average summary length | 280 chars | Short paragraph summary |
| Average author/editor length | 18 chars | User/display name |
| Average branch name length | 32 chars | Feature branch style |
| Average base branch length | 6 chars | `main` / `master` class |
| Average reviewers | 3 | Typical small review team |
| Average commits in primary repo | 8 | Stored as one commit-list entry |
| Average comment threads per review | 12 | File-level review comments |
| Average text entries per comment thread | 1.6 | Includes replies |
| Threads with resolution status stream | 35% | Only some comments become discussions needing resolution |

### Encoding / storage assumptions

| Assumption | Value | Notes |
|---|---:|---|
| `StreamEntry` JSON wrapper overhead | ~110 bytes / entry | ID + timestamp + editor + JSON field names |
| Git/object/ref overhead multiplier | +45% | Loose objects, refs, tree/object duplication, small-file inefficiency |
| Effective transfer throughput | 10 MB/s | Reasonable sustained WAN/VPN estimate |
| Metadata refresh cost | ~0.35 s / review | Based on observed local timings for `readAllMetadata` / refresh flows |
| Retained in-memory review index | ~1.5 KB / review | Merged `ReviewItem` + list/map overhead |
| Transient refresh footprint | ~3.5 KB / review | Includes per-repository copies + merge structures |

### Why 0.35 s per review?

Observed local logs in this workspace show:

- `readAllMetadata extractNoteToFile (8 streams, parallel)` often landing in the `~0.1s - 0.6s` range
- refreshing a small set of reviews in `~3s`

So `0.35 s / review` is a reasonable planning estimate for the current implementation when reading and merging review metadata locally.

---

## Derived Per-Review Cost Model

### Primary repository metadata

| Component | Estimated size |
|---|---:|
| Title entry | ~0.18 KB |
| Author entry | ~0.13 KB |
| Description entry | ~0.39 KB |
| Status entry | ~0.12 KB |
| Primary repository entry | ~0.11 KB |
| Branch entry | ~0.14 KB |
| Base branch entry | ~0.12 KB |
| Commit list entry | ~0.47 KB |
| Reviewer entries (3 avg) | ~0.48 KB |
| **Primary metadata subtotal** | **~2.14 KB** |

### Secondary repository metadata

Per secondary repository, the baseline assumes:

- `metadata/primaryRepository=false`
- `metadata/branch`
- `metadata/baseBranch`

| Component | Estimated size |
|---|---:|
| One secondary repo subtotal | ~0.37 KB |
| Two secondary repos subtotal | ~0.74 KB |

### Comment thread data

Average per thread:

| Component | Estimated size |
|---|---:|
| Comment metadata entry | ~0.24 KB |
| Comment text entries (1.6 avg) | ~0.47 KB |
| Comment status amortized | ~0.05 KB |
| **Per thread subtotal** | **~0.76 KB** |

For 12 comment threads:

- `12 × 0.76 KB = ~9.1 KB`

### Total review payload

| Category | Estimated size |
|---|---:|
| Primary metadata | ~2.14 KB |
| Secondary metadata | ~0.74 KB |
| Comment data | ~9.10 KB |
| **Raw NDJSON payload** | **~11.98 KB** |
| Git/object/ref overhead (+45%) | ~5.39 KB |
| **Estimated stored size / review** | **~17.37 KB** |

Rounded planning value:

- **~17 KB per review** stored

---

## Ref Count Model

### Average refs per review

Baseline ref count:

| Source | Average refs |
|---|---:|
| Primary metadata/reviewer refs | 9 |
| Secondary repo refs (2 × 3) | 6 |
| Comment thread refs | 28 |
| **Average total refs / review** | **~43 refs** |

This is one of the most important scaling metrics in the current design.

At high review counts, ref enumeration and note lookup cost will dominate sooner than raw byte size does.

---

## Scale Table: Persistent Storage and Ref Count

| Reviews | Stored size @ ~17 KB/review | Approx note refs @ ~43/review |
|---:|---:|---:|
| 100 | ~1.7 MB | ~4,300 |
| 1,000 | ~16.9 MB | ~43,000 |
| 10,000 | ~169 MB | ~430,000 |
| 100,000 | ~1.69 GB | ~4.3 million |
| 1,000,000 | ~16.9 GB | ~43 million |

### Interpretation

- **Storage volume alone is not the main problem** up to 1M reviews.
- **Ref count absolutely is a problem** by 100k+ reviews.
- Git operations such as `show-ref`, `for-each-ref`, notes fetch, and many note lookups will degrade sharply as ref count grows.

---

## Scale Table: Estimated Cold Transfer Volume and Transfer Time

Assumption:

- effective sustained Git transfer throughput = **10 MB/s**
- this is a payload-only approximation; many small refs/objects can make real transfer slower

| Reviews | Transfer volume | Estimated cold transfer time |
|---:|---:|---:|
| 100 | ~1.7 MB | ~0.2 s |
| 1,000 | ~16.9 MB | ~1.7 s |
| 10,000 | ~169 MB | ~17 s |
| 100,000 | ~1.69 GB | ~2.8 min |
| 1,000,000 | ~16.9 GB | ~28.2 min |

### Important caveat

These times are lower bounds.

Because the current design creates many small note refs and objects, real Git fetch performance may be worse than bandwidth-only math suggests. At higher review counts, latency, ref advertisement, object negotiation, and many small file/object reads can dominate.

---

## Scale Table: Reviews Panel / Metadata Refresh Time

Assumption:

- effective current metadata query cost = **0.35 seconds per review**
- this is based on observed timings in the current code path
- this reflects the current architecture, not an optimized indexed solution

| Reviews | Estimated full refresh/query time |
|---:|---:|
| 100 | ~35 s |
| 1,000 | ~5.8 min |
| 10,000 | ~58 min |
| 100,000 | ~9.7 h |
| 1,000,000 | ~97 h (~4 days) |

### Interpretation

Under the current query model:

- **100 reviews** is comfortable
- **1,000 reviews** is noticeable but still manageable for many workflows
- **10,000 reviews** becomes operationally painful without caching/indexing
- **100,000+ reviews** is beyond what a full scan/refresh approach should attempt

---

## Supplemental Scenario: External Indexer + Change Notification Plugin

This section models the planned scaling approach where Git notes remain the source of truth, but list/query workloads are handled by an external indexer (or custom plugin service) that:

- consumes review changes from Git notes
- maintains a materialized routing + summary index
- notifies clients of changed review IDs
- lets clients hydrate only needed reviews from Git when required

### Assumptions

- index stores a compact summary row per review (`reviewId`, primary repository, status, branch/base branch, last update, minimal display fields)
- average index row size = **~0.8 KB**
- client refresh path reads only changed IDs, not all reviews
- average changed reviews per refresh window:
  - 100k environment: **250**
  - 1M environment: **2,500**
- targeted metadata hydration cost remains dominated by per-review work, but applies only to changed/visible items

### Index storage estimate

| Reviews | Index size @ ~0.8 KB/review |
|---:|---:|
| 100 | ~80 KB |
| 1,000 | ~0.8 MB |
| 10,000 | ~8 MB |
| 100,000 | ~80 MB |
| 1,000,000 | ~800 MB |

### Incremental refresh estimate (index-driven)

| Reviews | Changed IDs per refresh | Estimated client refresh |
|---:|---:|---:|
| 100,000 | ~250 | ~1-4 s |
| 1,000,000 | ~2,500 | ~8-30 s |

### Interpretation

- The external indexer shifts cost from global scan (`O(total reviews)`) to incremental update + targeted hydration (`O(changed IDs)`)
- This is the key enabler for responsive behavior at 100k+
- Without this layer, refresh/query latency dominates before raw storage does

---

## Scale Table: In-Memory Processing Cost

Assumptions:

- retained merged review item state = **~1.5 KB / review**
- transient refresh/indexing state = **~3.5 KB / review**

| Reviews | Retained heap estimate | Transient refresh estimate |
|---:|---:|---:|
| 100 | ~150 KB | ~350 KB |
| 1,000 | ~1.5 MB | ~3.5 MB |
| 10,000 | ~15 MB | ~35 MB |
| 100,000 | ~150 MB | ~350 MB |
| 1,000,000 | ~1.5 GB | ~3.5 GB |

### Interpretation

At 1M reviews, memory is already a problem even before accounting for:

- Strings and duplication across repositories
- JVM overhead variability
- UI model copies
- comment data loaded for open reviews
- Git subprocess buffers and temporary extracted files

---

## Additional Size Overhead Breakdown

The current architecture has several forms of overhead beyond the user-visible review content:

### 1. NDJSON entry envelope overhead

Every append includes:

- UUIDv7 ID
- timestamp
- editor name
- JSON field names and punctuation

This makes small logical fields relatively expensive.

Examples:

- `status = "open"` is only a few bytes logically, but still becomes a full `StreamEntry<String>` record
- `primaryRepository = true/false` is tiny logically, but still becomes a full record and a dedicated note ref

### 2. One-stream-per-concern overhead

A review is not stored as one blob. It is split across many streams:

- title
- author
- description
- status
- branch
- base branch
- commits
- primary flag
- reviewers
- per-comment metadata/text/status

This is excellent for append-only history and partial updates, but expensive for:

- note ref count
- fetch/merge fan-out
- per-review Git command count

### 3. Secondary repository duplication

Multi-repository reviews duplicate at least branch/base/primary marker metadata into every secondary repo.

This is necessary for discoverability, but it increases:

- total refs
- storage size
- merge complexity
- refresh cost

### 4. Temporary extraction overhead

The application frequently extracts note content to temporary NDJSON files under the temp directory before reading them. This adds:

- extra file I/O
- temp storage churn
- CPU cost from repeated serialization/deserialization

---

## Query-Time Observations by Use Case

### Reviews Panel refresh

This is the most scale-sensitive operation.

Why it gets expensive:

- review discovery is repository-wide
- metadata is read review-by-review
- many Git subprocesses are involved
- merged review state must be reassembled across repositories

### Code Review Panel open

Opening a single review is less sensitive to total review count, provided the review ID is already known.

Typical dominant work:

- load primary review metadata
- load comments
- fetch target branches if needed
- compute changed files / commit scope

For a single review, this usually scales more with:

- number of repositories in the review
- number of changed files
- number of comment threads

than with total global review count.

### Save / append operations

Single review updates are relatively cheap in storage terms, but they increase:

- entry count per stream
- ref/object churn
- future read/merge cost for long-lived reviews

---

## Practical Capacity Guidance

### Up to ~1,000 reviews

Current architecture is reasonable.

Expected behavior:

- storage trivial
- transfer acceptable
- refresh latency noticeable but workable
- memory comfortable

### Around ~10,000 reviews

Current architecture begins to show friction.

Likely symptoms:

- refresh operations become slow enough to annoy users
- Git notes ref enumeration becomes noticeable
- per-review subprocess overhead dominates wall-clock time

### Around ~100,000 reviews

Current architecture needs additional indexing or partitioning.

Needed measures likely include:

- review index snapshot / materialized catalog
- repository sharding by age or project
- paging and filtering before metadata hydration
- background incremental index maintenance

### Around ~1,000,000 reviews

Current architecture is not a good direct fit without substantial redesign.

Why:

- ~43 million note refs is extremely heavy
- full metadata scans become operationally impractical
- heap pressure becomes material
- transfer and Git negotiation costs become significant

At this level, Git notes can still be a historical backing store, but not the only query surface.

---

## Design Conclusions

### Storage conclusion

**Raw storage is acceptable much longer than query cost is.**

Even 1M reviews only projects to roughly **17 GB** of stored review notes in the baseline model, which is manageable on modern storage.

### Query conclusion

**The current read path is the real limit.**

With the present refresh/query model, practical comfort is likely in the:

- **hundreds** to **low thousands** of reviews range

Beyond that, indexing and ref-count reduction become more important than raw storage optimization.

With an external indexer/custom plugin, practical query scalability can extend into higher ranges because clients avoid full metadata scans.

### Ref-count conclusion

**Ref count is the primary scale risk.**

If there is one metric to watch first, it is not bytes; it is:

- note refs per review
- total refs per repository
- Git command count per refresh

---

## Recommended Next-Step Design Questions

If the project ever targets 10k+ long-lived reviews, the next design step should evaluate:

1. **Materialized review catalog**
   - one append-only summary stream per repository
   - avoid scanning all per-review streams for list views

2. **Ref consolidation**
   - reduce number of note refs per review
   - especially for comments

3. **Paged review discovery**
   - load IDs and summaries first
   - hydrate full metadata lazily

4. **Age-based partitioning**
   - active vs archived reviews
   - keep hot repositories smaller

5. **Secondary-repo minimization**
   - preserve discoverability while reducing duplicated metadata footprint

6. **External indexer/custom plugin contract**
   - define push/pull contract for review routing + summary updates
   - define notification model for changed review IDs
   - define fallback behavior when indexer is unavailable

---

## Baseline Summary

For the current architecture, a reasonable planning summary is:

- **~17 KB stored per review** under baseline assumptions
- **~43 Git note refs per review** on average
- **~0.35 s metadata refresh cost per review** in the current implementation
- storage scales well enough into very large counts
- refresh/query behavior does **not** scale well enough without indexing/ref consolidation

That means:

- **100 to 1,000 reviews**: comfortable
- **10,000 reviews**: needs optimization work
- **100,000 reviews**: needs architectural support
- **1,000,000 reviews**: needs a different query strategy, even if Git notes remain the source of truth

Given the current direction, the external indexer/custom plugin is the preferred architectural support for 100k+ review scales.




