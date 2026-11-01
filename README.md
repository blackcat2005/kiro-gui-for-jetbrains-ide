# Kiro GUI for JetBrains IDEs

A JetBrains IDE plugin that gives [Kiro](https://kiro.dev) a native chat GUI. It talks to
`kiro-cli acp` over the [Agent Client Protocol](https://agentclientprotocol.com) (JSON-RPC over stdio),
so it needs no embedded browser and no local HTTP server.

Requires IDE build 243+ (2024.3 and newer) and an installed, signed-in `kiro-cli`.

## Features

- **Chat** with streaming Markdown (headings, tables, quotes), **syntax-coloured code blocks** with a language label
  and a Copy button, Stop button, Enter to send / Shift+Enter for a newline, and a scroll position that stays put
  while you read.
- **File names in answers are links**: Ctrl+click (Cmd+click on macOS) opens the file in the editor, at the line
  when the answer says `File.kt:42`. Only names that resolve to a real file in the project are linked.
- **Agent and model pickers**, context usage % and credits per session, and your **plan's credits** for the
  billing cycle, top right of the chat (bar + `30.6 / 50 credits · 61%`, amber from 80%, red from 95%; click to
  refresh). The header icons are on the left, with the session-list toggle first.
- **Diff review before edits**: when Kiro asks permission to create or change a file, the change opens
  as an IDE diff with Kiro's own Yes / Always / No buttons.
- **Typing helpers**: `/` at the start suggests Kiro's slash commands and your skills (and subcommands after
  `/context `); `@` suggests project files (recently opened first) and skills. **Send to Kiro** (`Ctrl+Alt+K`)
  puts the selected code, file and line range into the input.
- **Session sidebar** beside the chat: click a session to reopen it, **New chat**, folders (drag a session onto a
  folder to file it), the open session highlighted. Hide or show it with the header button (top right).
  A clicked session's history appears at once (read from Kiro's files) while Kiro reloads it in the background;
  sessions Kiro can't reload, such as tangents in the v3 store, open read-only. New chats and reopened sessions
  reuse the running `kiro-cli` process (about a second instead of two to three).
- **Library** tab: one tree for everything Kiro keeps.
  - *Sessions*: your own nestable folders, unfiled sessions grouped by date, tangents and subagent sessions
    under the session they branched from; rename and move via right-click. Sessions in Kiro's legacy store can
    be reopened with their history; those in the newer v3 store can be organised and renamed but not reopened
    (the `kiro-cli acp` server can't load them).
  - *Skills, Steering, Specs (with task progress), Hooks* under `.kiro/`: create, open, delete, enable/disable.

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
