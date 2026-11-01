# Kiro GUI for JetBrains IDEs

A JetBrains IDE plugin that gives [Kiro](https://kiro.dev) a native chat GUI. It talks to
`kiro-cli acp` over the [Agent Client Protocol](https://agentclientprotocol.com) (JSON-RPC over stdio),
so it needs no embedded browser and no local HTTP server.

Requires IDE build 243+ (2024.3 and newer) and an installed, signed-in `kiro-cli`.

## Features

- **Chat** with streaming Markdown, Stop button, Enter to send / Shift+Enter for a newline,
  and a scroll position that stays put while you read.
- **Agent and model pickers**, context usage % and credits per session.
- **Diff review before edits**: when Kiro asks permission to create or change a file, the change opens
  as an IDE diff with Kiro's own Yes / Always / No buttons.
- **Slash commands and skills** from a `/` menu; **Send to Kiro** (`Ctrl+Alt+K`) puts the selected code,
  file and line range into the chat input.
- **Sessions**: your Kiro sessions as a tree, with your own (nestable) folders, rename, and tangents /
  subagent sessions shown under the session they branched from. Sessions in Kiro's legacy store can be
  reopened with their history; sessions in the newer v3 store can be organised and renamed but not reopened
  (the `kiro-cli acp` server can't load them).
- **Manage** skills, steering files, specs (with task progress) and hooks under `.kiro/`.

## Install

Download `kiro-gui-for-jetbrains-ide-<version>.zip` from the Releases page, then
*Settings -> Plugins -> gear -> Install Plugin from Disk...* and restart. Set the path to `kiro-cli` in
*Settings -> Tools -> Kiro* (an absolute path is safest; IDEs often don't inherit your shell PATH).

## Build

```
./gradlew test buildPlugin     # zip in build/distributions
./gradlew runIde               # try it in a sandbox IDE
```

## Notes on where things are stored

- Folders and display names for sessions: `~/.kiro/kirogui-sessions.json` (the plugin's own file; Kiro's
  session files are never moved). Renaming also writes Kiro's own `title` so the CLI shows the same name.
- Skills, steering, specs and hooks are plain files under `.kiro/` in the project and in `~/.kiro`.
