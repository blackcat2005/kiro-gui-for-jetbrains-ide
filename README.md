# Kiro GUI for JetBrains IDEs

A JetBrains IDE plugin that gives [Kiro](https://kiro.dev) a native chat GUI. It talks to
`kiro-cli acp` over the [Agent Client Protocol](https://agentclientprotocol.com) (JSON-RPC over stdio),
so it needs no embedded browser and no local HTTP server.

Requires IDE build 251+ (2025.1 and newer) and an installed, signed-in `kiro-cli` (2.x or 3.x).

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
- **Attachments**: the Attach button, `@` and Ctrl+V add files, selected code and images (screenshots) as chips above
  the input; the open editor file is attached automatically (turn it off in Settings). Long pastes become a chip too.
  Shift+Enter inserts a newline. Diffs in answers are coloured rows with *Copy diff* / *Copy new*.
- **Notification** when Kiro finishes a reply (balloon and system notification, by default only while the IDE is in
  the background).
- **Library** tab: one tree for everything Kiro keeps.
  - *Sessions*: your own nestable folders, unfiled sessions grouped by date, tangents and subagent sessions
    under the session they branched from; rename and move via right-click. Sessions in Kiro's legacy store can
    be reopened with their history; those in the newer v3 store can be organised and renamed but not reopened
    (the `kiro-cli acp` server can't load them).
  - *Skills, Steering, Specs (with task progress), Hooks* under `.kiro/`: create, open, delete, enable/disable.
  - *Agents* (`.kiro/agents/*.json`, workspace and global): model, tool count and MCP servers per agent; the agent
    the chat is using is marked **in use**.
  - *MCP servers* (`settings/mcp.json`, workspace and global): each shows **on** or **off**. Off means the entry has
    `"disabled": true`, or a workspace entry with the same name replaces it. The list is read from the files, so it
    shows what is configured, not whether the server connected; a server that fails to connect is reported in the chat.

## Settings (Settings -> Tools -> Kiro)

- **kiro-cli path, arguments, environment**: arguments go after `acp` (for example `--agent my-agent`); one
  `KEY=VALUE` per line for the environment.
- **Run through**: start kiro-cli through PowerShell, cmd, bash/Git Bash, WSL or a custom shell template with `{cmd}`.
  This only changes the shell that starts kiro-cli, not the shell kiro-cli uses for the commands its agent runs.
- **Agent engine**: kiro-cli 3.x has two engines (`--agent-engine v2` / `v3`). Leave it on the default unless
  something misbehaves.
- **Start timeout** (default 120 s): how long to wait for kiro-cli to start and to open a session. Raise it where
  security software slows kiro-cli down.
- Chat font and size, attaching the open file, and notifications.

## kiro-cli 3.x

The plugin understands both generations of the protocol and shows the same thing for each: the model list and model
switching, context %, credits per turn, slash commands, and warnings such as an MCP server that failed to connect.
kiro-cli 3.1 chooses its engine from the client name; with this plugin it is the v3 engine unless you pick v2 in
Settings. The plan-credits bar (your account's credits for the billing cycle) is not available on the v3 engine yet and
stays hidden there. Notes on kiro-cli's environment variables are in [docs/kiro-cli-v3-env.md](docs/kiro-cli-v3-env.md).

## Troubleshooting

- **"Cannot start kiro-cli" or a timeout**: open *Help -> Show Log in Explorer* and look for the lines `Starting kiro-cli`,
  `kiro-cli initialized in ... ms` and `Session ... ready in ... ms` in `idea.log`; they show where it stalled. On a
  managed machine, security software may be slowing or blocking `kiro-cli`; raise the start timeout and ask your IT
  team to allow it.
- **Not signed in**: run `kiro-cli login` in a terminal, then press Retry.
- A "Restart" or "Retry" also stops the processes kiro-cli started (its engine and MCP servers).

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

## License

MIT, see [LICENSE](LICENSE).

## Publishing to JetBrains Marketplace

1. `./gradlew buildPlugin` and upload `build/distributions/*.zip` by hand for the first release, or
2. set `PUBLISH_TOKEN` (a token from your JetBrains Marketplace profile) and run `./gradlew publishPlugin`.
   Plugin signing is optional; set `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD` to use `signPlugin`.
