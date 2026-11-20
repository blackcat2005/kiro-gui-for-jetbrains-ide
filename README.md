# Kiro GUI for JetBrains IDEs

A JetBrains IDE plugin that gives [Kiro](https://kiro.dev) a native chat GUI. It talks to
`kiro-cli acp` over the [Agent Client Protocol](https://agentclientprotocol.com) (JSON-RPC over stdio),
so it needs no embedded browser. The only thing it listens on is a loopback-only MCP endpoint that gives Kiro the
IDE's tools (background commands and the debugger, see below); it can be turned off.

**New here? Start with the [user guide](docs/USER_GUIDE.md)**: install, first chat, and everyday use, step by step.

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

## Memory (kiro-cli 3.x)

Kiro can remember your preferences and facts about a project across chats. Memory is off in Kiro until you choose:
*Settings → Tools → Kiro → Kiro's memory* offers off, read only, and read and write (optionally letting Kiro save new
memories by itself). The choice is sent per chat (`session/new` `_meta.kiro.settings.memory`), so the plugin never edits
Kiro's own settings files. The Library's **Memories** section lists what Kiro remembers (`_kiro/memory/list`); open one
to read it in full, or delete it. Memories belong to Kiro, so they are shared with `kiro-cli` in a terminal.

## Background jobs and the debugger

Kiro can hand slow work to the IDE and keep chatting. The plugin serves a few MCP tools to each chat (on
`127.0.0.1`, one secret URL per chat, so a call knows which chat it came from):

- **`bg_run`** starts a shell command (PowerShell on Windows, bash elsewhere) and returns at once. It runs in the
  Run tool window, where you can watch and stop it. `job_status` shows a job's output, `job_stop` ends it.
- **`debug_start`** runs one of your run configurations under the IDE debugger. `debug_breakpoint` adds line
  breakpoints, conditional ones and tracepoints (log a value without stopping). While the program is paused,
  `debug_state`, `debug_eval` and `debug_control` (step, resume, pause, stop) read and drive it. They also work on a
  debug session you started yourself. Each stop also shows the last lines the program printed.
  **`debug_run_until`** does the whole start in one call (breakpoints, run, first stop with stack and variables), which
  saves most of the round trips; `debug_control` with `action=wait` waits for the next stop.
- **`debug_task`** hands an open-ended debugging hunt to a separate Kiro session on a cheaper model (a haiku one by
  default, or the model set in Settings → Kiro). That helper has only the IDE tools (it cannot edit files or run
  shell commands) and the main chat gets its short report as a normal job result. Every call a helper makes costs the
  helper's rate, not the main model's.
- **Keeping the tools in use**: the plugin keeps an always-on steering file (`~/.kiro/steering/kirogui-ide-tools.md`) up
  to date (only if it is an older copy of its own text), which survives Kiro's compaction. When Kiro compacts a chat
  (`/compact` in the chat input calls the real compaction) the next prompt carries a two-line reminder.
- **`test_run`** runs tests with the IDE's own test runner, whatever the project uses (JUnit, TestNG, Gradle, pytest,
  Jest, …), picked the way the editor's "Run tests" actions pick it. With no target it runs all of the project's
  tests; it can also run a file, a folder, the single test at `file:line`, or a test run configuration. When the run
  ends Kiro gets a report rather than raw output: how many passed and failed and, for each failure, the test, its file
  and line, the assertion message and the stack frames in the project's own code. If the build fails it gets the
  compile errors instead. `rerun_failed` runs only the tests that failed in an earlier run, so the loop is: run all,
  fix, rerun the failures, run all again.
- **`run_logs`** reads the console of anything run or debugged in the IDE since the project opened, including runs
  you started yourself: application logs, server output, stack traces. It can pick out lines containing a text.
  Lines are numbered from the start of each run, and the plugin remembers per chat how far Kiro has read, so
  `new_only` (also on `job_status`) returns only what was printed since. A debugger stop likewise shows only console
  lines Kiro hasn't seen yet.

The **Jobs** panel (the ⚙ Run-icon button in the tool window title, beside the chat when the window is wide and under it when narrow) lists these jobs from every chat with their state, how long they have
run and their live output. It opens by itself when Kiro starts a job, and the chat's "N background jobs running" line
shows or hides it. From there you can stop a job, bring up its console in the Run or Debug tool window (double-click
does the same), attach its output to your next message, or clear the ended ones.

**Which shell?** Kiro's `bg_run` commands run in PowerShell on Windows and bash elsewhere. If PowerShell is blocked where
you work, pick another one in Settings → Tools → Kiro → "Shell for Kiro's commands": Git Bash (found in the usual Git for
Windows folders, or next to `git` on the PATH), Command Prompt, PowerShell 7, WSL, or your own template such as
`C:/Tools/bash.exe -c {cmd}`. This covers the commands Kiro starts through the plugin; commands Kiro runs with its own
built-in shell tool are up to kiro-cli.

## Several chats at once

The Kiro tool window is one workspace: the **sessions and library** list on the left (shared by every chat), the chats as
tabs in the middle, the **jobs** on the right (underneath when the window is narrow). Three buttons in the tool window
title show or hide them: **+** (new chat), the session list, the jobs. A tab carries the name the list shows for its session (your rename, else the title Kiro gave it, else the first question).

The **+** opens another chat tab. Each tab has its own conversation with Kiro (they share one
kiro-cli process, and kiro-cli runs sessions side by side), so you can ask one chat something long while you carry on in
another. A tab shows a spinner while its chat is working. A session is opened in the tab that already has it, and when
you pick one from the list while the current chat is busy it opens in a new tab instead of asking to stop. Right-click a
session for "Open in new chat tab". "New chat" in the list or the **+** reuses the current tab if it is still empty. The last tab can't be closed, and closing a busy one asks first. Restarting kiro-cli
from one chat ends the sessions of the others, which then offer to reconnect.

Nothing blocks while these run. When a command ends, or the debugger stops at a breakpoint in a session Kiro is
driving, the plugin queues a short note for that chat. It sends the note to Kiro as soon as the chat is idle, and the
transcript shows it as a ⚙ line. Kiro then reads the exit code and output, or the location, stack and variables, and
carries on. If you step or resume in the debugger yourself, the following stops are yours and Kiro is not woken until
it acts again. A note for a chat that isn't open waits until you open it, and a balloon tells you about it. After you
press Stop, notes wait until you send your next message. A note that Kiro no longer needs is dropped instead of
interrupting it: news of a job ending when Kiro has since started the same command again, or of a debug run that
simply finished when Kiro has since started something newer.

This exists because kiro-cli can't do it on its own. Its `run_in_background` either still waits for the command (when
the client runs commands) or loses the result, it doesn't support MCP Tasks, and nothing wakes an idle agent. A second
prompt sent mid-turn cancels the turn, so the plugin sends all prompts for a chat, yours and the IDE's, strictly one
after another.

## Settings (Settings -> Tools -> Kiro)

- **kiro-cli path, arguments, environment**: arguments go after `acp` (for example `--agent my-agent`); one
  `KEY=VALUE` per line for the environment.
- **Run through**: start kiro-cli through PowerShell, cmd, bash/Git Bash, WSL or a custom shell template with `{cmd}`.
  This only changes the shell that starts kiro-cli, not the shell kiro-cli uses for the commands its agent runs.
- **Agent engine**: kiro-cli 3.x has two engines (`--agent-engine v2` / `v3`). Leave it on the default unless
  something misbehaves.
- **Start timeout** (default 120 s): how long to wait for kiro-cli to start and to open a session. Raise it where
  security software slows kiro-cli down.
- **IDE tools**: give Kiro background commands and the debugger (on by default). Applies to chats opened afterwards.
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
