# Better Server Browser — Mindustry Mod

Replaces Mindustry's vanilla **Join Game** dialog with a sortable / filterable / groupable server table. Designed for players who hop between many servers and want to read live state at a glance.

## Features

- **p90 ping**, computed over a rolling 16-sample window. Continuous re-ping with exponential backoff (1s..15min plateau) — values stay current without hammering.
- **Sortable columns**: Server, Players, Mode, Ping, Map. Click a header to sort; click again to flip direction.
- **Mode-chip filters**: every observed mode (vanilla `Gamemode` + custom server-supplied `modeName`) gets its own toggle chip. Selected = visible.
- **Search** by server name (case-insensitive substring).
- **Max-ping slider** + **show-empty toggle**.
- **Group-by**: none, mode, or server group (community list grouping).
- **Favorites** in user-chosen order, synced bidirectionally with the vanilla `servers` settings key — your existing favorites carry over and stay in sync.
- **Custom connect dialog** for `host[:port]` entry, with **+ Favorite** button.
- **Recent connections** (last 16) are auto-listed for one-click reconnect.
- **Row hover highlight** spans the full row including inter-cell padding.
- **Reconnect** button on the main menu — shows the last successfully-connected server with live player count + ping refresh.

## Build

Requires JDK 17+ on `PATH` and `Mindustry.jar` in the parent directory.

### Windows

```cmd
build.bat
```

### Manual

```bash
javac --release 17 -cp ../Mindustry.jar -d build/classes src/betterserverbrowser/*.java
cp mod.hjson build/classes/
jar cf BetterServerBrowser.jar -C build/classes .
```

Output: `BetterServerBrowser.jar`.

## Install

Copy `BetterServerBrowser.jar` into your Mindustry mods directory:

- Windows: `%AppData%\Mindustry\mods\`
- Linux: `~/.local/share/Mindustry/mods/`
- macOS: `~/Library/Application Support/Mindustry/mods/`

Restart Mindustry. The mod replaces the **Join Game** dialog automatically — no extra UI to enable.

## How it works

The mod hooks `Trigger.update`, polls `Vars.ui.join.isShown()`, and intercepts the moment the vanilla `JoinDialog` is mounted. It hides that dialog and opens its own. Every other entry path (Play menu, `gh pr create` URL handler, etc.) routes through the same `JoinDialog.show()`, so they all redirect transparently.

Community servers are fetched directly from `Vars.serverJsonURLs` (the same source the vanilla browser uses).

## Configuration

Persisted to `Core.settings` under the `bsb.*` namespace. No config UI — everything is set via the dialog itself (chip toggles, slider, sort headers, etc.).

## Compatibility

- Mindustry `minGameVersion: 154` (current stable v8 line).
- Java mod (`java: true`).
- No content additions — pure UI / network mod.

## License

MIT.
