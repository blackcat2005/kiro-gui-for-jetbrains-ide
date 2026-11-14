package com.kirogui.jetbrains.jobs

/** One thing Kiro can do with the IDE's tools, as the empty chat shows it: a title, a line about it, a prompt to try. */
data class ToolCard(
    val title: String,
    val blurb: String,
    /** Put into the input box when the card is clicked (the user finishes it and sends). */
    val prompt: String,
    /** The MCP tools behind it; only the ones that are switched on are listed. */
    val tools: List<String>,
)

/** What the empty chat tells a new user about the tools this plugin gives Kiro. */
object ToolShowcase {
    val ALL = listOf(
        ToolCard(
            "Understand your code",
            "Follows the IDE's real index (definitions, usages, call graphs) instead of grepping text.",
            "Who calls the function I have open, and what would break if I changed it?",
            listOf("find_definition", "find_references", "find_implementations", "call_graph", "file_structure", "find_symbol", "project_modules", "editor_state"),
        ),
        ToolCard(
            "Run your tests",
            "Uses the IDE's test runner. Kiro gets a short report of what failed and where, not a wall of output.",
            "Run all the tests and tell me which ones fail and why.",
            listOf("test_run"),
        ),
        ToolCard(
            "Debug for real",
            "Sets breakpoints, runs under the IDE debugger and reads the variables, instead of adding print statements.",
            "Debug this: <what goes wrong>. Set breakpoints, run it under the debugger and look at the variables.",
            listOf("debug_run_until", "debug_breakpoint", "debug_state", "debug_eval", "debug_control", "debug_exceptions", "debug_start"),
        ),
        ToolCard(
            "Debug on a budget",
            "Hands a bug hunt to a cheaper model that uses the debugger and reports back.",
            "Use debug_task to find out why <what goes wrong>.",
            listOf("debug_task"),
        ),
        ToolCard(
            "Long jobs in the background",
            "Builds, servers and slow commands run while you keep chatting; Kiro is told when they finish.",
            "Run the build in the background and tell me when it is done.",
            listOf("bg_run", "job_status", "job_stop", "run_logs"),
        ),
    )

    /** The cards worth showing when [enabled] tools are on: a card with none of its tools on is left out. */
    fun available(enabled: Set<String>): List<ToolCard> = ALL.mapNotNull { card ->
        val on = card.tools.filter { it in enabled }
        if (on.isEmpty()) null else card.copy(tools = on)
    }
}
