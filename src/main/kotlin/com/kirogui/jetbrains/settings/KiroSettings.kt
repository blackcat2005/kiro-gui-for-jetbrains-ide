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

        /** The file in the selected editor tab is attached to every prompt (a chip you can dismiss). */
        var autoAttachOpenFile: Boolean = true

        /** Balloon when a reply finishes; by default only while you are looking elsewhere. */
        var notifyOnDone: Boolean = true
        var notifyOnlyInBackground: Boolean = true

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
        val TOPIC: Topic<Listener> = Topic.create("Kiro GUI settings", Listener::class.java)

        fun getInstance(): KiroSettings = service()
    }
}
