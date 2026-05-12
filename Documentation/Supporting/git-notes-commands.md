# Git Notes Commands — Serverless Review Tool

All review data is stored in git notes under `refs/notes/reviews/<REVIEW_ID>/`.
Every stream is an **NDJSON** file attached as a git note to the repository's **root commit**.

> Single-line `git` commands work on any shell. Where shell processing is needed, bash and PowerShell variants are shown separately.

## Ref Structure

```
refs/notes/reviews/<REVIEW_ID>/
  metadata/title
  metadata/description
  metadata/author
  metadata/status
  metadata/commits
  metadata/branch
  metadata/baseBranch
  metadata/primaryRepository
  reviewers
  comments/<COMMENT_ID>/metadata
  comments/<COMMENT_ID>/text
  comments/<COMMENT_ID>/status
```

---

## Step 1 — Configure Git to Auto-Fetch Review Notes

Do this once per clone so `git fetch` and `git pull` automatically sync all review streams:

```
git config --add remote.origin.fetch "+refs/notes/reviews/*:refs/notes/reviews/*"
```

To make `git log` show review notes inline:

```
git config notes.displayRef "refs/notes/reviews/*"
```

---

## Step 2 — Fetch Notes from Remote

These commands fetch individual streams when you already know the `REVIEW_ID`.
To fetch **all reviews at once** without knowing any IDs yet, skip ahead to Step 4.

Fetch a single stream (e.g. status only):

```
git fetch origin refs/notes/reviews/<REVIEW_ID>/metadata/status:refs/notes/reviews/<REVIEW_ID>/metadata/status
```

Fetch all core metadata streams for a review — run one line per stream:

```
git fetch origin refs/notes/reviews/<REVIEW_ID>/metadata/title:refs/notes/reviews/<REVIEW_ID>/metadata/title
git fetch origin refs/notes/reviews/<REVIEW_ID>/metadata/description:refs/notes/reviews/<REVIEW_ID>/metadata/description
git fetch origin refs/notes/reviews/<REVIEW_ID>/metadata/author:refs/notes/reviews/<REVIEW_ID>/metadata/author
git fetch origin refs/notes/reviews/<REVIEW_ID>/metadata/status:refs/notes/reviews/<REVIEW_ID>/metadata/status
git fetch origin refs/notes/reviews/<REVIEW_ID>/metadata/branch:refs/notes/reviews/<REVIEW_ID>/metadata/branch
git fetch origin refs/notes/reviews/<REVIEW_ID>/metadata/baseBranch:refs/notes/reviews/<REVIEW_ID>/metadata/baseBranch
git fetch origin refs/notes/reviews/<REVIEW_ID>/metadata/commits:refs/notes/reviews/<REVIEW_ID>/metadata/commits
git fetch origin refs/notes/reviews/<REVIEW_ID>/reviewers:refs/notes/reviews/<REVIEW_ID>/reviewers
```

Fetch with union merge strategy (safe when concurrent changes may exist):

```
git -c notes.mergeStrategy=union fetch origin refs/notes/reviews/<REVIEW_ID>/metadata/title:refs/notes/reviews/<REVIEW_ID>/metadata/title
```

---

## Step 3 — Find the Root Commit

All notes are attached to the root commit of the repository:

```
git rev-list --max-parents=0 HEAD
```

Store it as a variable for use in subsequent commands:

**bash / Linux**
```bash
ROOT=$(git rev-list --max-parents=0 HEAD)
```

**PowerShell / Windows**
```powershell
$ROOT = git rev-list --max-parents=0 HEAD
```

---

## Step 4 — Fetch All Reviews at Once

Before you can list review IDs, all note refs need to exist locally.
Use this single wildcard fetch to pull **every review** from the remote in one command:

```
git fetch origin refs/notes/reviews/*:refs/notes/reviews/*
```

> If your shell expands the `*` glob before git sees it, quote it:
>
> **bash / Linux**
> ```bash
> git fetch origin 'refs/notes/reviews/*:refs/notes/reviews/*'
> ```
>
> **PowerShell / Windows**
> ```powershell
> git fetch origin "refs/notes/reviews/*:refs/notes/reviews/*"
> ```

After fetching, verify refs are now present locally:

```
git for-each-ref refs/notes/reviews/
```

---

## Step 5 — List All Review IDs

Once refs are present locally (after Step 4):

List all refs for a single known review:

```
git for-each-ref --format="%(refname)" refs/notes/reviews/<REVIEW_ID>/
```

List all unique review IDs:

**bash / Linux**
```bash
git for-each-ref --format="%(refname:lstrip=3)" refs/notes/reviews/ | cut -d'/' -f1 | sort -u
```

**PowerShell / Windows**
```powershell
git for-each-ref --format="%(refname:lstrip=3)" refs/notes/reviews/ | ForEach-Object { ($_ -split '/')[0] } | Sort-Object -Unique
```

List all review IDs with last-updated date:

**bash / Linux**
```bash
git for-each-ref --format="%(creatordate:short) %(refname:lstrip=3)" refs/notes/reviews/ | cut -d'/' -f1-2 | sort -u | sort -r
```

**PowerShell / Windows**
```powershell
git for-each-ref --format="%(creatordate:short) %(refname:lstrip=3)" refs/notes/reviews/ | ForEach-Object { ($_ -split '/')[0..1] -join '/' } | Sort-Object -Unique | Sort-Object -Descending
```

---

## Step 6 — Read Metadata for a Review

Set `$ROOT` / `ROOT` first (see Step 3).
All commands below work on any shell once the variable is set.

### Title
```
git notes --ref=refs/notes/reviews/<REVIEW_ID>/metadata/title show $ROOT
```

### Description
```
git notes --ref=refs/notes/reviews/<REVIEW_ID>/metadata/description show $ROOT
```

### Author
```
git notes --ref=refs/notes/reviews/<REVIEW_ID>/metadata/author show $ROOT
```

### Status
```
git notes --ref=refs/notes/reviews/<REVIEW_ID>/metadata/status show $ROOT
```

### Branch under review
```
git notes --ref=refs/notes/reviews/<REVIEW_ID>/metadata/branch show $ROOT
```

### Base branch it is compared against
```
git notes --ref=refs/notes/reviews/<REVIEW_ID>/metadata/baseBranch show $ROOT
```

### Commits included in the review
```
git notes --ref=refs/notes/reviews/<REVIEW_ID>/metadata/commits show $ROOT
```

### Primary repository flag
```
git notes --ref=refs/notes/reviews/<REVIEW_ID>/metadata/primaryRepository show $ROOT
```

---

## Step 7 — Read Reviewers

Show all reviewer decisions (raw NDJSON stream):

```
git notes --ref=refs/notes/reviews/<REVIEW_ID>/reviewers show $ROOT
```

---

## Step 8 — Read Comments

List all comment IDs for a review:

**bash / Linux**
```bash
git for-each-ref --format="%(refname:lstrip=3)" refs/notes/reviews/<REVIEW_ID>/comments/ | cut -d'/' -f1 | sort -u
```

**PowerShell / Windows**
```powershell
git for-each-ref --format="%(refname:lstrip=3)" refs/notes/reviews/<REVIEW_ID>/comments/ | ForEach-Object { ($_ -split '/')[0] } | Sort-Object -Unique
```

Show comment text for a specific comment:

```
git notes --ref=refs/notes/reviews/<REVIEW_ID>/comments/<COMMENT_ID>/text show $ROOT
```

Show comment location (file, line, commit):

```
git notes --ref=refs/notes/reviews/<REVIEW_ID>/comments/<COMMENT_ID>/metadata show $ROOT
```

Show comment resolution status:

```
git notes --ref=refs/notes/reviews/<REVIEW_ID>/comments/<COMMENT_ID>/status show $ROOT
```

---

## Full Review Dump

**bash / Linux** — save as `dump-review.sh`, run with `./dump-review.sh <REVIEW_ID>`:

```bash
#!/usr/bin/env bash
REVIEW_ID="$1"
ROOT=$(git rev-list --max-parents=0 HEAD)
streams=("metadata/title" "metadata/description" "metadata/author" "metadata/status" "metadata/branch" "metadata/baseBranch" "metadata/commits" "metadata/primaryRepository" "reviewers")

echo "=== Review: $REVIEW_ID ==="
for stream in "${streams[@]}"; do
  echo ""
  echo "--- $stream ---"
  git notes --ref="refs/notes/reviews/$REVIEW_ID/$stream" show "$ROOT" 2>/dev/null || echo "(empty)"
done

echo ""
echo "--- comments ---"
comment_ids=$(git for-each-ref --format="%(refname:lstrip=3)" "refs/notes/reviews/$REVIEW_ID/comments/" | cut -d'/' -f1 | sort -u)
for cid in $comment_ids; do
  echo "  comment: $cid"
  for part in metadata text status; do
    echo "    [$part]"
    git notes --ref="refs/notes/reviews/$REVIEW_ID/comments/$cid/$part" show "$ROOT" 2>/dev/null || echo "    (empty)"
  done
done
```

**PowerShell / Windows** — save as `dump-review.ps1`, run with `.\dump-review.ps1 <REVIEW_ID>`:

```powershell
param([string]$ReviewId)
$root = git rev-list --max-parents=0 HEAD
$streams = @("metadata/title","metadata/description","metadata/author","metadata/status","metadata/branch","metadata/baseBranch","metadata/commits","metadata/primaryRepository","reviewers")

Write-Host "=== Review: $ReviewId ===" -ForegroundColor Cyan
foreach ($stream in $streams) {
    Write-Host ""
    Write-Host "--- $stream ---" -ForegroundColor Yellow
    $out = git notes "--ref=refs/notes/reviews/$ReviewId/$stream" show $root 2>$null
    if ($out) { $out } else { Write-Host "(empty)" -ForegroundColor DarkGray }
}

Write-Host ""
Write-Host "--- comments ---" -ForegroundColor Yellow
$commentIds = git for-each-ref --format="%(refname:lstrip=3)" "refs/notes/reviews/$ReviewId/comments/" | ForEach-Object { ($_ -split '/')[0] } | Sort-Object -Unique
foreach ($cid in $commentIds) {
    Write-Host "  comment: $cid" -ForegroundColor Cyan
    foreach ($part in @("metadata","text","status")) {
        Write-Host "    [$part]" -ForegroundColor Yellow
        $out = git notes "--ref=refs/notes/reviews/$ReviewId/comments/$cid/$part" show $root 2>$null
        if ($out) { $out } else { Write-Host "    (empty)" -ForegroundColor DarkGray }
    }
}
```

---

## Push Notes to Remote

Push a single stream:

```
git push origin refs/notes/reviews/<REVIEW_ID>/metadata/status
```

Push all streams for a review:

**bash / Linux**
```bash
git for-each-ref --format="%(refname)" refs/notes/reviews/<REVIEW_ID>/ | xargs -I{} git push origin {}
```

**PowerShell / Windows**
```powershell
git for-each-ref --format="%(refname)" refs/notes/reviews/<REVIEW_ID>/ | ForEach-Object { git push origin $_ }
```

Optimistic push with lease (prevents overwriting concurrent changes):

**bash / Linux**
```bash
REF=refs/notes/reviews/<REVIEW_ID>/metadata/status
EXPECTED=$(git rev-parse --verify $REF 2>/dev/null || echo "0000000000000000000000000000000000000000")
git push --force-with-lease=$REF:$EXPECTED origin $REF
```

**PowerShell / Windows**
```powershell
$ref = "refs/notes/reviews/<REVIEW_ID>/metadata/status"
$expected = git rev-parse --verify $ref 2>$null
if (-not $expected) { $expected = "0000000000000000000000000000000000000000" }
git push "--force-with-lease=${ref}:${expected}" origin $ref
```

---

## Recover / Reset Notes

Force-fetch a stream from remote, overwriting local (useful after a conflict):

```
git fetch origin +refs/notes/reviews/<REVIEW_ID>/metadata/status:refs/notes/reviews/<REVIEW_ID>/metadata/status
```

Delete a single note ref locally:

```
git update-ref -d refs/notes/reviews/<REVIEW_ID>/metadata/title
```

Delete all local note refs for a review:

**bash / Linux**
```bash
git for-each-ref --format="%(refname)" refs/notes/reviews/<REVIEW_ID>/ | xargs -I{} git update-ref -d {}
```

**PowerShell / Windows**
```powershell
git for-each-ref --format="%(refname)" refs/notes/reviews/<REVIEW_ID>/ | ForEach-Object { git update-ref -d $_ }
```

---

## Browse All Refs

Show all review-related refs with SHA and creation date:

```
git for-each-ref --format="%(objectname:short) %(creatordate:relative) %(refname)" refs/notes/reviews/
```

Show a commit log with review notes inlined:

```
git log --notes=refs/notes/reviews/<REVIEW_ID>/metadata/title --show-notes
```




