# Manual Syntax Highlighter Harness

`SyntaxHighlighterManualTestHarness` is a Swing viewer for manual visual checks of syntax colors.

The harness lives in `ReviewToolApplication` test sources and uses `PluginManager` to load syntax plugins from the configured plugins directory.

## Covered extensions

- `java`
- `py`
- `ts`
- `js`
- `html`
- `css`
- `tsx`
- `json`
- `yaml`
- `yml`
- `xml`
- `shell`
- `bash`
- `sh`
- `vue`
- `rs` (Rust)
- `golang`
- `go`
- `cpp` (C++)
- `sql`
- `md` (Markdown)
- `toml`
- `ini`
- `dockerfile`
- `kt` (Kotlin)
- `kts` (Kotlin Script)
- `cs` (C#)
- `rb` (Ruby)
- `php`
- `swift`
- `scala`
- `lua`
- `ps1` (PowerShell)
- `idl`
- `ada`


## Usage

Run `manualtests.SyntaxHighlighterManualTestHarness` from IDE test sources.

Features:

- language/extension selector
- editable source text
- highlighted output pane
- dark/light toggle
- active plugin status label





