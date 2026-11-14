package com.kirogui.jetbrains.jobs

/** The steering file that tells Kiro how to use the IDE tools this plugin serves, and the short reminder sent after a compaction. */
object ToolGuidance {
    const val FILE_NAME = "kirogui-ide-tools"

    /** Bumped when [CONTENT] changes; an older file that carries our marker is rewritten, anything else is left alone. */
    const val VERSION = 4

    /** Marks a file as ours, so it is only ever replaced when it is an older copy of this text. */
    const val MARKER_PREFIX = "<!-- kirogui-guidance v"

    val CONTENT = """
---
inclusion: always
---
$MARKER_PREFIX$VERSION -->
# JetBrains IDE tools (MCP server `kirogui`)

The IDE that hosts this chat serves tools that know the real project. Use them instead of guessing, and keep
using them after the conversation is summarised.

- Getting to know a project: `project_modules`, then `list_dir`, then `file_structure` of the main files (names and
  signatures, not bodies), then `find_references` / `call_graph` from the entry points. Use `read_file` only for the
  bodies you need; do not read every file one by one.
- Code: `file_structure`, `find_definition`, `find_references`, `find_implementations`, `call_graph` follow the
  language's own index; use them instead of grep. `find_symbol` / `find_file` look names up, `editor_state` shows what the
  user has open.
- Tests: `test_run` (the IDE runner), never a shell command. Long commands: `bg_run`.
- Debugging: ONE call `debug_run_until` (breakpoints + run + first stop with stack and variables), then `debug_eval` /
  `debug_control`. Do not add print statements. For a long investigation hand it over with `debug_task`
  (a cheaper model does the stepping and reports back).
- `bg_run`, `test_run`, `debug_start`, `debug_task` return at once and the IDE sends you a message when they finish or
  the debugger stops. Do not poll, sleep or wait.
- `run_logs` reads any run console, or a bg_run job (`job: <id>`); pass `new_only: true`.
""".trimStart()

    /** Prepended once to the first prompt after the conversation was compacted (no extra turn, so no extra cost). */
    const val REMINDER = "[IDE reminder: prefer the `kirogui` MCP tools: project_modules / list_dir / file_structure to get to know a project (not read_file on every file), file_structure / find_definition / " +
        "find_references / call_graph instead of grep, test_run instead of a test command, debug_run_until / debug_task " +
        "instead of print statements, bg_run for long commands. They return at once and the IDE messages you when done.]"

    /** [text] without the reminder this plugin put in front of a prompt, so a reloaded session doesn't show it as the user's words. */
    fun withoutReminder(text: String): String {
        if (!text.startsWith("[IDE reminder")) return text
        val end = text.indexOf("]\n\n")
        return if (end < 0) text else text.substring(end + 3).trimStart()
    }

    /** True when [existing] is a copy this plugin wrote at an older [VERSION] (so it may be overwritten). */
    fun isOutdatedCopy(existing: String): Boolean {
        val at = existing.indexOf(MARKER_PREFIX)
        if (at < 0) return existing.contains("# Using the JetBrains IDE tools (MCP server `kirogui`)") // the unmarked first version
        val version = existing.substring(at + MARKER_PREFIX.length).takeWhile { it.isDigit() }.toIntOrNull() ?: return true
        return version < VERSION
    }
}
