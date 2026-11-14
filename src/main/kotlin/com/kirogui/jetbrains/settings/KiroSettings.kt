package com.kirogui.jetbrains.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic

@Service(Service.Level.APP)
@State(name = "KiroGuiSettings", storages = [Storage("kiro-gui.xml")])
class KiroSettings : PersistentStateComponent<KiroSettings.State> {

    class State {
        /** Command or absolute path of the Kiro CLI. IDEs often lack the shell PATH, so absolute is safest. */
        var kiroPath: String = "kiro-cli"

        /** Extra arguments placed after `acp`, shell-style quoting (e.g. `--agent my-agent --trust-all-tools`). */
        var kiroArgs: String = ""

        /** Extra environment variables for the process, one `KEY=VALUE` per line. */
        var kiroEnv: String = ""

        /** Shell template that kiro-cli is started through (`bash -l -c {cmd}`); blank starts it directly. */
        var kiroShell: String = ""

        /**
         * Shell Kiro's background commands (bg_run) run in, e.g. `git-bash -c {cmd}` or `cmd /c {cmd}`. Blank:
         * PowerShell on Windows, bash elsewhere. Some companies block PowerShell.
         */
        var jobShell: String = ""

        /** `--agent-engine` value for kiro-cli 3.x (`v1`, `v2`, `v3`); blank leaves the choice to kiro-cli. */
        var agentEngine: String = ""

        /** How long to wait for kiro-cli to start and to open or reload a session, in seconds. */
        var startTimeoutSeconds: Int = DEFAULT_START_TIMEOUT

        /**
         * Kiro gets the IDE's own tools (an MCP server inside the IDE): shell commands that run in the background
         * and the debugger. Kiro is told when a job ends or the debugger stops, so the chat never blocks on them.
         */
        var ideTools: Boolean = true

        /** Keep the always-on steering file about the IDE tools in `~/.kiro/steering` (it survives a compaction). */
        var autoGuidance: Boolean = true

        /**
         * Approve every permission request from Kiro without asking (what `--trust-all-tools` does on the v2 engine, which
         * the v3 engine lacks). File edits then skip the diff review too.
         */
        var trustAllTools: Boolean = false

        /** Also create that steering file in the project's `.kiro/steering` (it overrides the global one; usually committed). */
        var guidanceInProject: Boolean = false

        /**
         * Model the `debug_task` helper session uses; blank picks a cheap one (a model whose name has "haiku", else
         * the cheapest-looking). Anything the chat's model list offers works.
         */
        var workerModel: String = ""

        /** What debug_eval may evaluate: `safe` (blocks processes, files, network, reflection), `readonly` or `unrestricted`. */
        var evalMode: String = "safe"

        /** IDE tools Kiro does not get, by name (e.g. `debug_eval, bg_run`); applies to the next tools/list or call. */
        var disabledTools: String = ""

        /** Draw the chat in the IDE's embedded browser (rounded cards, timeline); off uses the older Swing view. */
        var webChat: Boolean = true

        /** The file in the selected editor tab is attached to every prompt (a chip you can dismiss). */
        var autoAttachOpenFile: Boolean = true

        /** Balloon when a reply finishes; by default only while you are looking elsewhere. */
        var notifyOnDone: Boolean = true
        var notifyOnlyInBackground: Boolean = true

        /** Open the chats that were open when the project was last closed (their sessions are reloaded). */
        var restoreChats: Boolean = true

        /** Chat font; blank family / zero size mean "follow the IDE font" (size: one point larger). */
        var chatFontFamily: String = ""
        var chatFontSize: Int = 0
    }

    /** Published after the settings page is applied, so open chats can restyle themselves. */
    fun interface Listener {
        fun settingsChanged()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun notifyChanged() {
        ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).settingsChanged()
    }

    companion object {
        const val DEFAULT_START_TIMEOUT = 120
        const val MIN_START_TIMEOUT = 10
        const val MAX_START_TIMEOUT = 1800

        val TOPIC: Topic<Listener> = Topic.create("Kiro GUI settings", Listener::class.java)

        fun getInstance(): KiroSettings = service()
    }
}
