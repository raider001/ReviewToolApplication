# UI Design

## Overview

This document defines the user interface design for the Serverless Code Review Tool. The design is framework-agnostic and can be implemented in any GUI technology (JavaFX, Swing, Qt, WPF, Electron, etc.).

---

## Application Forms

### 1. Main Application Window

Primary window containing all views, navigation, and controls.

**Layout**:
```
┌─────────────────────────────────────────────────────────────┐
│ Menu Bar: [File] [Edit] [View] [Help]                       │
├─────────────────────────────────────────────────────────────┤
│ Toolbar: [New Review] [Refresh] [Settings]                  │
├─────────────────────────────────────────────────────────────┤
│ ╔═══════════════════════════════════════════════════════╗   │
│ ║ Tab Bar: [Reviews] [Create Review] [Review: OAuth2…] ║   │
│ ╠═══════════════════════════════════════════════════════╣   │
│ ║                                                       ║   │
│ ║                                                       ║   │
│ ║                                                       ║   │
│ ║           Main Content Area (Tab Content)            ║   │
│ ║                                                       ║   │
│ ║                                                       ║   │
│ ║                                                       ║   │
│ ║                                                       ║   │
│ ║                                                       ║   │
│ ╚═══════════════════════════════════════════════════════╝   │
├─────────────────────────────────────────────────────────────┤
│ Status Bar: [Git: origin/main] [Synced: 2 min ago] [●Live] │
└─────────────────────────────────────────────────────────────┘
```

**Components**:

- **Menu Bar**: Standard application menus
  - File: New Review, Open Repository, Exit
  - Edit: Preferences/Settings
  - View: Refresh, Filter Options, Theme Toggle
  - Help: Documentation, About

- **Toolbar**: Quick access buttons for common actions
  - New Review: Opens create review form
  - Refresh: Sync with Git remote
  - Settings: Opens settings dialog

- **Tab Bar**: Dynamic tabs showing open views
  - "Reviews" tab: Always present, shows reviews list
  - "Create Review" tab: Opens when creating new review
  - Individual review tabs: One per open review (closeable)

- **Main Content Area**: Displays content for selected tab
  - Reviews List (tab 1)
  - Create Review Form (tab 2)
  - Review Detail Views (tabs 3+)

- **Status Bar**: Shows application status
  - Git repository info (current branch, remote)
  - Last sync timestamp
  - Connection status indicator
  - Notification count

---

### 2. Reviews List

Main landing page showing all code reviews with filtering and search capabilities.

**Layout**:
```
┌─────────────────────────────────────────────────────────────┐
│ Toolbar: [New Review] [Refresh] [Filter ▼]                  │
├─────────────────────────────────────────────────────────────┤
│ Filter Bar: Status: [All ▼] Author: [All ▼] Search: [____] │
├─────────────────────────────────────────────────────────────┤
│ ╔════════════════════════════════════════════════════════╗  │
│ ║ Review List (Table/TreeView)                           ║  │
│ ║ ┌──────────────────────────────────────────────────┐  ║  │
│ ║ │ ● Pending   │ Add OAuth2 login flow with PKCE   │  ║  │
│ ║ │   John Doe  │ 2 reviewers │ 5 comments │ 3h ago │  ║  │
│ ║ ├──────────────────────────────────────────────────┤  ║  │
│ ║ │ ✓ Approved  │ Fix memory leak in cache manager  │  ║  │
│ ║ │   Jane S.   │ 3 reviewers │ 12 comments │ 1d    │  ║  │
│ ║ ├──────────────────────────────────────────────────┤  ║  │
│ ║ │ ⚠ Changes   │ Refactor database connection pool │  ║  │
│ ║ │   Bob Lee   │ 2 reviewers │ 8 comments │ 2d    │  ║  │
│ ║ └──────────────────────────────────────────────────┘  ║  │
│ ╚════════════════════════════════════════════════════════╝  │
│ [Double-click to open review]                               │
└─────────────────────────────────────────────────────────────┘
```

---

### 3. Create Review

Form to create a new code review by selecting commits and assigning reviewers.

**Layout**:
```
┌─────────────────────────────────────────────────────────────┐
│ Create New Code Review                                       │
├─────────────────────────────────────────────────────────────┤
│ Review Strategy: (○) Branch  (●) Commits                     │
├─────────────────────────────────────────────────────────────┤
│ ┌─ Branch Mode ─────────────────────────────────────────┐   │
│ │ Branch to Review:        [feature/oauth2-login    ▼] │   │
│ │ Compare Against:         [main                    ▼] │   │
│ │                                                       │   │
│ │ Commits in this review: 3 commits                    │   │
│ │ [View Commits...]                                    │   │
│ └───────────────────────────────────────────────────────┘   │
│                                                              │
│ ┌─ Review Details ───────────────────────────────────────┐  │
│ │ Title: [_____________________________________________] │  │
│ │                                                        │  │
│ │ Description (Markdown supported):                     │  │
│ │ ┌────────────────────────────────────────────────┐   │  │
│ │ │ Implements OAuth2 authentication with PKCE...  │   │  │
│ │ │                                                 │   │  │
│ │ │                                                 │   │  │
│ │ └────────────────────────────────────────────────┘   │  │
│ │                                                        │  │
│ │ Reviewers:                                             │  │
│ │ ┌─────────────────────┐  ┌──────────────────────┐    │  │
│ │ │ Available:          │  │ Selected:             │    │  │
│ │ │ □ alice@example.com │  │ ☑ bob@example.com     │    │  │
│ │ │ □ charlie@ex.com    │  │ ☑ diana@example.com   │    │  │
│ │ │ ...                 │  │                       │    │  │
│ │ └─────────────────────┘  └──────────────────────┘    │  │
│ │          [Add >]  [< Remove]                          │  │
│ └────────────────────────────────────────────────────────┘  │
│                                                              │
│               [Cancel]  [Preview]  [Create Review]          │
└─────────────────────────────────────────────────────────────┘
```

---

### 4. Review Detail

Main interface for reviewing code, adding comments, and approving/rejecting changes.

**Layout**:
```
┌─────────────────────────────────────────────────────────────┐
│ ← Back to List │ Review: Add OAuth2 login flow with PKCE    │
├─────────────────────────────────────────────────────────────┤
│ Toolbar: [Edit] [Approve] [Request Changes] [Close] [⚙]    │
├─────────────────────────────────────────────────────────────┤
│ ┌─ Review Info ─────────────────────────────────────────┐   │
│ │ Author: John Doe (john@example.com)                   │   │
│ │ Status: ⚠ Changes Requested                           │   │
│ │ Created: 2026-04-23 14:30 │ Updated: 2026-04-23 16:45│   │
│ │ Strategy: Branch (feature/oauth2 → main)              │   │
│ │ Commits: 3 commits (abc123...ghi789)                  │   │
│ └───────────────────────────────────────────────────────┘   │
│                                                              │
│ ┌─ Description ──────────────────────────────────────────┐  │
│ │ Implements OAuth2 authentication with PKCE flow for   │  │
│ │ enhanced security. Key changes:                        │  │
│ │ - Added PKCE code verifier/challenge generation       │  │
│ │ - Updated token exchange endpoint                     │  │
│ │ [Show History...] [Edit...]                           │  │
│ └────────────────────────────────────────────────────────┘  │
│                                                              │
│ ┌─ Reviewers ────────────────────────────────────────────┐  │
│ │ ✓ Alice Smith     │ Approved         │ 2h ago │ LGTM! │  │
│ │ ⚠ Bob Johnson     │ Changes Req.     │ 1h ago │ Tests?│  │
│ │ ⏳ Diana Lee       │ Pending          │ -      │       │  │
│ └────────────────────────────────────────────────────────┘  │
│                                                              │
│ Tabs: [Overview] [Commits] [Files] [Comments] [Activity]   │
│ ┌────────────────────────────────────────────────────────┐  │
│ │ <Tab content area - dynamic based on selected tab>    │  │
│ │                                                         │  │
│ │                                                         │  │
│ │                                                         │  │
│ │                                                         │  │
│ └────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

**Tab Content**:

#### Overview Tab
```
┌────────────────────────────────────────────────────────┐
│ Summary Statistics:                                     │
│ • 3 commits                                             │
│ • 12 files changed (+245, -89)                         │
│ • 8 comments (3 unresolved)                            │
│ • 3 reviewers (1 approved, 1 changes requested)        │
│                                                         │
│ Recent Activity:                                        │
│ • Bob Johnson requested changes         - 1h ago       │
│ • Alice Smith approved                   - 2h ago       │
│ • John Doe updated description           - 3h ago       │
│ • John Doe added commit ghi789           - 4h ago       │
│                                                         │
│ [View All Activity...]                                 │
└────────────────────────────────────────────────────────┘
```

#### Commits Tab
```
┌────────────────────────────────────────────────────────┐
│ Commits in this review:                                │
│ ┌──────────────────────────────────────────────────┐  │
│ │ ● abc123d  Add OAuth2 client registration       │  │
│ │            John Doe - 2 days ago                 │  │
│ │   [View Diff] [View in Repo]                    │  │
│ ├──────────────────────────────────────────────────┤  │
│ │ ● def456g  Implement PKCE code generation       │  │
│ │            John Doe - 2 days ago                 │  │
│ │   [View Diff] [View in Repo]                    │  │
│ ├──────────────────────────────────────────────────┤  │
│ │ ● ghi789j  Update token exchange endpoint       │  │
│ │            John Doe - 1 day ago                  │  │
│ │   [View Diff] [View in Repo]                    │  │
│ └──────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────┘
```

#### Files Tab
```
┌────────────────────────────────────────────────────────┐
│ Files changed: (Tree view with folders)                │
│ ┌──────────────────────────────────────────────────┐  │
│ │ ▼ src/                                           │  │
│ │   ▼ auth/                                        │  │
│ │     📄 oauth2_client.py         +87  -12  [3] 💬│  │
│ │     📄 pkce.py                   +145 -0   [2] 💬│  │
│ │   ▼ api/                                         │  │
│ │     📄 token.py                  +13  -77  [1] 💬│  │
│ │ ▼ tests/                                         │  │
│ │   📄 test_oauth2.py              +245 -0   [2] 💬│  │
│ └──────────────────────────────────────────────────┘  │
│                                                         │
│ Click file to view diff with inline comments →        │
└────────────────────────────────────────────────────────┘
```

#### Comments Tab
```
┌────────────────────────────────────────────────────────┐
│ Filter: [All Comments ▼] Sort: [Newest First ▼]       │
│ ┌──────────────────────────────────────────────────┐  │
│ │ 💬 Alice Smith - 2h ago - src/auth/oauth2_clie…  │  │
│ │    Consider adding validation for redirect_uri   │  │
│ │    📄 oauth2_client.py:42                        │  │
│ │    ⤷ John: Good point, will add.                │  │
│ │       [Resolve] [Reply] [View in Context]       │  │
│ ├──────────────────────────────────────────────────┤  │
│ │ ⚠ Bob Johnson - 1h ago - tests/test_oauth2.py   │  │
│ │    Missing test cases for error scenarios        │  │
│ │    📄 test_oauth2.py:15                          │  │
│ │    [Resolve] [Reply] [View in Context]          │  │
│ ├──────────────────────────────────────────────────┤  │
│ │ 💡 Diana Lee - 30m ago - general comment         │  │
│ │    Overall looks good, just those test cases    │  │
│ │    [Reply]                                       │  │
│ └──────────────────────────────────────────────────┘  │
│                                                         │
│ [Add General Comment...]                               │
└────────────────────────────────────────────────────────┘
```

#### Activity Tab
```
┌────────────────────────────────────────────────────────┐
│ Activity Timeline:                                      │
│ ┌──────────────────────────────────────────────────┐  │
│ │ 🕐 1h ago - Bob Johnson                          │  │
│ │    ⚠ Requested changes                           │  │
│ │    "Please add tests for error scenarios"        │  │
│ ├──────────────────────────────────────────────────┤  │
│ │ 🕐 2h ago - Alice Smith                          │  │
│ │    ✓ Approved                                    │  │
│ │    "LGTM! Good implementation."                  │  │
│ ├──────────────────────────────────────────────────┤  │
│ │ 🕐 3h ago - John Doe                             │  │
│ │    ✏ Updated description                         │  │
│ ├──────────────────────────────────────────────────┤  │
│ │ 🕐 4h ago - John Doe                             │  │
│ │    📝 Added commit ghi789                        │  │
│ │    "Update token exchange endpoint"              │  │
│ ├──────────────────────────────────────────────────┤  │
│ │ 🕐 1d ago - John Doe                             │  │
│ │    ✨ Created review                             │  │
│ └──────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────┘
```

---

### 5. File Diff

Shows file changes with inline commenting on specific lines.

**Layout**:
```
┌─────────────────────────────────────────────────────────────┐
│ File: src/auth/oauth2_client.py                             │
│ [Split View] [Unified View] [Side-by-Side]                  │
├─────────────────────────────────────────────────────────────┤
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ Line│ Old │ New │ Code                                   │ │
│ ├─────────────────────────────────────────────────────────┤ │
│ │  40 │  40 │  40 │ def register_client(self, redirect_… │ │
│ │  41 │  41 │  41 │     """Register OAuth2 client."""   │ │
│ │  42 │  42 │  42 │     self.client_id = generate_id()  │ │
│ │     │     │     │ 💬 Alice: Consider validation…  [▼] │ │
│ │     │     │     │    ⤷ John: Good point, will add.    │ │
│ │     │     │     │    [Reply] [Resolve] [+]            │ │
│ │     │     │  43 │+    if not redirect_uri:            │ │
│ │     │     │  44 │+        raise ValueError("Invalid") │ │
│ │  43 │  43 │  45 │     self.redirect_uri = redirect_…  │ │
│ │  44 │  44 │  46 │     return self.client_id           │ │
│ │     │     │     │ [Add comment on this line...]       │ │
│ └─────────────────────────────────────────────────────────┘ │
│                                                              │
│ [Prev Comment] [Next Comment] [Prev File] [Next File]      │
└─────────────────────────────────────────────────────────────┘
```

---

### 6. Edit Review Metadata

Dialog for editing review title, description, commits, and reviewers.

**Layout**:
```
┌─────────────────────────────────────────────────────────┐
│ Edit Review Metadata                                     │
├─────────────────────────────────────────────────────────┤
│ Title: [Add OAuth2 login flow with PKCE____________]    │
│                                                          │
│ Description:                                             │
│ ┌──────────────────────────────────────────────────┐   │
│ │ Implements OAuth2 authentication with PKCE flow…│   │
│ │                                                   │   │
│ └──────────────────────────────────────────────────┘   │
│                                                          │
│ Commits: (3 selected)                                    │
│ ┌──────────────────────────────────────────────────┐   │
│ │ ☑ abc123d  Add OAuth2 client registration       │   │
│ │ ☑ def456g  Implement PKCE code generation       │   │
│ │ ☑ ghi789j  Update token exchange endpoint       │   │
│ │ □ jkl012m  Update docs (not in review)          │   │
│ └──────────────────────────────────────────────────┘   │
│ [Add Commit] [Remove Selected]                          │
│                                                          │
│ Reviewers:                                               │
│ ┌──────────────────────────────────────────────────┐   │
│ │ ☑ alice@example.com    (Approved)                │   │
│ │ ☑ bob@example.com      (Changes Requested)       │   │
│ │ ☑ diana@example.com    (Pending)                 │   │
│ └──────────────────────────────────────────────────┘   │
│ [Add Reviewer] [Remove Selected]                        │
│                                                          │
│                    [Cancel]  [Save Changes]             │
└─────────────────────────────────────────────────────────┘
```

---

### 7. Settings

Application settings for Git configuration, sync preferences, and appearance.

**Layout**:
```
┌─────────────────────────────────────────────────────────┐
│ Settings                                                 │
├─────────────────────────────────────────────────────────┤
│ Tabs: [General] [Git] [Appearance] [Sync] [About]      │
│ ┌───────────────────────────────────────────────────┐  │
│ │ General:                                           │  │
│ │ Username:  [John Doe_______________]              │  │
│ │ Email:     [john@example.com_______]              │  │
│ │                                                    │  │
│ │ Git:                                               │  │
│ │ Repository Path: [/path/to/repo___] [Browse...]  │  │
│ │ Remote Name:     [origin___________]              │  │
│ │                                                    │  │
│ │ Sync:                                              │  │
│ │ Auto-sync: [✓] Enable automatic background sync  │  │
│ │ Interval:  [5___] minutes                         │  │
│ │ Notify on: [✓] New reviews  [✓] New comments     │  │
│ │                                                    │  │
│ │ Appearance:                                        │  │
│ │ Theme: [Light ▼]  (Light, Dark)                   │  │
│ │ Font Size: [12 ▼]                                 │  │
│ └───────────────────────────────────────────────────┘  │
│                      [Cancel]  [Save]                   │
└─────────────────────────────────────────────────────────┘
```

---

### 8. Notification Banner

In-app banner for new reviews or comments.

**Layout**:
```
┌─────────────────────────────────────────────────────────┐
│ ℹ️ 2 new reviews available. [View] [Dismiss]            │
└─────────────────────────────────────────────────────────┘
```

---

### 9. Toast Notification

Popup notification for new activity.

**Layout**:
```
┌───────────────────────────────┐
│ 💬 New comment on review      │
│ "Add OAuth2 login flow"       │
│ By: Alice Smith               │
│ [View] [Dismiss]              │
└───────────────────────────────┘
```

---

### 10. History Viewer

Shows edit history for review metadata fields.

**Layout**:
```
┌─────────────────────────────────────────────────────────┐
│ Title History                                            │
├─────────────────────────────────────────────────────────┤
│ ┌─────────────────────────────────────────────────────┐ │
│ │ 📅 2026-04-24 09:15 - John Doe (current)           │ │
│ │    "Implement OAuth2 with PKCE support"            │ │
│ ├─────────────────────────────────────────────────────┤ │
│ │ 📅 2026-04-23 16:45 - John Doe                     │ │
│ │    "Add OAuth2 login flow with PKCE"               │ │
│ ├─────────────────────────────────────────────────────┤ │
│ │ 📅 2026-04-23 14:30 - John Doe (original)          │ │
│ │    "Add OAuth2 login flow"                         │ │
│ └─────────────────────────────────────────────────────┘ │
│                                                          │
│ [Revert to Selected] [Close]                            │
└─────────────────────────────────────────────────────────┘
```

---

*Document Status: **DRAFT***










