# Kiro GUI for JetBrains IDEs: User Guide

A friendly walk-through for people who just want to use it. For the full feature list and technical notes, see the
[README](../README.md).

**In one sentence:** this plugin puts [Kiro](https://kiro.dev) in a chat window inside your JetBrains IDE, so you can ask
Kiro to read, explain, change and test your code without leaving the editor.

---

## 1. Before you start

You need three things:

| | |
|---|---|
| A JetBrains IDE | IntelliJ IDEA, PyCharm, WebStorm, GoLand, Android Studio… version **2025.1 or newer** |
| `kiro-cli` installed | Version 2.x or 3.x. [Get it from kiro.dev](https://kiro.dev) |
| A Kiro account | Sign in once in a terminal: `kiro-cli login` |

## 2. Install

1. Open **Settings → Plugins** (the ⚙ gear → **Install Plugin from Disk…** if you have the zip, or search **Kiro GUI**
   on the Marketplace tab).
2. Restart the IDE.
3. Open **Settings → Tools → Kiro** and set **kiro-cli path**. An absolute path is safest, for example
   `C:\Users\you\AppData\Local\Kiro-Cli\kiro-cli.exe` or `/usr/local/bin/kiro-cli`. IDEs often do not see the PATH of
   your terminal.

## 3. Your first chat

1. Open the **Kiro** tool window (right side of the IDE).
2. Wait a few seconds the first time while Kiro starts.
3. Type a question and press **Enter**. (**Shift+Enter** adds a new line.)

Good first questions:

- *"Explain what this project does and how it is organised."*
- *"Where is the login handled?"*
- *"Add a unit test for `OrderService.total`."*

While Kiro answers you can press **Stop** at any time.

> **Tip:** the file open in your editor is attached automatically, so "what does this do?" works without
> pasting anything. You can dismiss that chip, or turn the behaviour off in Settings.

## 4. Giving Kiro the right context

| You want to… | Do this |
|---|---|
| Point at a file | Type **`@`** and pick it from the list |
| Use a slash command or one of your skills | Type **`/`** at the start of the message |
| Send some code you selected | Select it, press **Ctrl+Alt+K** (**Send to Kiro**) |
| Attach a screenshot | **Ctrl+V** into the input, or use the **Attach** button |
| Paste a long log | Just paste. It becomes a compact chip instead of filling the input |

In Kiro's answers, **file names are links**: **Ctrl+click** (Cmd+click on macOS) opens the file, at the right line when
the answer says something like `OrderService.kt:42`.

## 5. Reviewing changes before they happen

When Kiro wants to create or change a file, the change opens as a normal **IDE diff**. Read it, then choose Kiro's own
buttons:

- **Yes**: allow this change
- **Always**: allow this kind of change from now on
- **No**: refuse it

Nothing is written to your files until you agree (unless you turned on *Trust all tools* in Settings, which skips the
review. Leave it off if you are unsure).

## 6. Choosing the agent and the model

At the top of the chat you can switch the **agent** (a preset with its own instructions and tools) and the **model**.
Next to them the plugin shows how full the conversation is (**context %**) and how many credits the session has used.
Click the credits bar to refresh it.

If the context gets full, type **`/compact`** in the chat: Kiro summarises the conversation to make room.

## 7. Several chats and your history

- The **+** button in the tool window title opens another chat in a new tab. Kiro can work on a long task in one tab
  while you carry on in another. A tab shows a spinner while it is busy.
- The **session list** (left side, toggle it with the title-bar button) holds all your past conversations. Click one
  to reopen it. Right-click to rename it, move it to a folder, or open it in a new tab.
- You can create **folders** and drag sessions into them.

## 8. Letting Kiro run things for you

Kiro can hand slow work to the IDE and carry on talking to you. You do not need to do anything special: just ask.

| Ask for… | What happens |
|---|---|
| *"Run the build and tell me if it fails."* | The command runs in the IDE's **Run** window. You can watch it. Kiro is told when it finishes. |
| *"Run the tests and fix the failures."* | Kiro uses the IDE's test runner and gets a short report: which tests failed, where, and why. |
| *"Debug this: it crashes when the list is empty."* | Kiro sets breakpoints, runs your configuration under the debugger, looks at the variables and steps through. |
| *"What did the server print?"* | Kiro reads the console of anything you ran or debugged in the IDE. |

The **Jobs** panel (the ⚙ button in the title bar) lists everything that is running, with live output. From there you
can stop a job, open its console, or attach its output to your next message.

If your company blocks PowerShell, choose another shell in **Settings → Tools → Kiro → Shell for Kiro's commands**
(for example Git Bash).

## 9. Memory: Kiro remembers things between chats

*Needs kiro-cli 3.x (the v3 engine).*

Memory lets Kiro keep useful facts so you do not have to repeat them in every chat:

- **Your preferences**: "I prefer tabs", "always answer in short bullet points".
- **Facts about a project**: "integration tests need the `docker compose up db` first".

### Turn it on

Memory is **off until you choose**. Go to **Settings → Tools → Kiro → Kiro's memory** and pick:

| Choice | Meaning |
|---|---|
| *(leave to Kiro's own setting)* | The plugin does not touch it. Kiro's default is off. |
| Off | Kiro uses no memory in chats opened by this plugin. |
| Read only | Kiro knows what it has remembered, but cannot add to it. |
| Read and write | Kiro can also remember new things when you ask ("remember that…"). |

With **Read and write** you can also tick *Let Kiro save new memories by itself*: after a while of working, Kiro notices
a preference or fact worth keeping and saves it.

The choice applies to **chats you open after changing it**.

### See and manage what Kiro remembers

Open the **Library** and find **Memories**:

- **Double-click** a memory to read it in full (what it says, where it applies, why, how to apply it).
- **Right-click → Forget this** to delete it.
- The filter box at the top searches memories too.

If the list says *"Not available: start a chat first"*, open a chat and press **Refresh** in the Library menu. The list
is read from the running `kiro-cli`.

### Good to know

- Memories are stored and managed by Kiro (`kiro-cli`), not by this plugin. The plugin only shows and deletes them.
- They are shared by every chat, and by `kiro-cli` in a terminal.
- Do not ask Kiro to remember passwords or keys. Kiro refuses to store things that look like credentials.
- Whether memories are also synced to your Kiro account is decided by your account and organisation settings, not by
  this plugin.

## 10. The Library

The **Library** is one tree of everything Kiro keeps for you. Right-click for actions, double-click to open.

| Section | What it is |
|---|---|
| Sessions | Your conversations, in your folders |
| Skills | Reusable how-to instructions Kiro can use |
| Steering | Always-on or conditional rules for Kiro (coding style, project conventions) |
| Specs | Feature plans with task progress |
| Hooks | Actions that run on events, such as saving a file |
| Agents | Your agent presets. The one in use is marked **in use** |
| MCP servers | Extra tool servers, showing **on** or **off** |
| Memories | What Kiro remembers (see above) |

Use the **New** (folder) button to create a skill, steering file, spec, hook or agent from a template.

## 11. Settings cheat sheet

**Settings → Tools → Kiro**. Most people only ever change the first line.

| Setting | What it does |
|---|---|
| kiro-cli path | Where `kiro-cli` is |
| Arguments / Environment | Extra options for `kiro-cli` (advanced) |
| Run through | Start `kiro-cli` through PowerShell, Git Bash, WSL… if your setup needs it |
| Agent engine | Leave on default unless something misbehaves |
| Start timeout | Raise it on slow or heavily protected machines |
| Chat font / size | Make the chat easier to read |
| Rounded chat view | The modern chat look. Turn off to use a simpler view if it misbehaves |
| Give Kiro the IDE's tools | Background commands, debugger and tests (on by default) |
| Trust all tools | Skip permission questions and diff review. **Off by default** |
| Kiro's memory | See section 9 |
| Notifications | A balloon when Kiro finishes a reply |

Changes to launch settings (path, arguments, shell, engine) apply the next time `kiro-cli` starts. Use **Restart** in the
chat header.

## 12. When something goes wrong

| What you see | Try this |
|---|---|
| "Cannot start kiro-cli" | Check **kiro-cli path** in Settings. Use the full path. |
| It hangs on start, or times out | Raise **Start timeout**. Security software on company machines can slow `kiro-cli` a lot: ask IT to allow it. |
| "Not signed in" | In a terminal run `kiro-cli login`, then press **Retry** in the chat. |
| The chat looks plain or old-style | Your IDE has no embedded browser (JCEF). The plugin switches to the simpler view by itself. Everything still works. |
| A file Kiro changed still shows old text | It refreshes after Kiro's tools finish. If not, use **File → Reload from Disk**. |
| Memories list is empty or "not available" | Memory needs kiro-cli 3.x and a running chat. It is also off until you turn it on (section 9). |
| Something else | Open **Help → Show Log in Explorer/Finder** and look in `idea.log` for lines containing `kiro`. These show where it stalled. Include them if you report an issue. |

A **Restart** (or **Retry**) in the chat header also stops the helper processes `kiro-cli` started.

## 13. Questions people ask

**Does the plugin send my code anywhere?** The plugin itself sends nothing. It runs the `kiro-cli` you installed, and
what `kiro-cli` sends to Kiro's service is governed by Kiro's own terms and settings. Your prompts, files and images
are passed to `kiro-cli` on your machine.

**Does it cost anything?** The plugin is free (MIT licence). Using Kiro uses your Kiro plan's credits; the chat header
shows what a session has used.

**Does it work on Windows, macOS and Linux?** Yes, anywhere the IDE and `kiro-cli` run.

**Can I use it with my own agents, skills and MCP servers?** Yes. It reads the same `.kiro` folders as `kiro-cli`, so
what you already set up shows up in the **Library**.

**Is it an official Kiro product?** No. It is an independent, community-made plugin, not affiliated with or endorsed by
Amazon Web Services or the Kiro team.

---

Found a problem or want a feature? Please open an issue on the project's repository and include the `idea.log` lines
mentioned above.
