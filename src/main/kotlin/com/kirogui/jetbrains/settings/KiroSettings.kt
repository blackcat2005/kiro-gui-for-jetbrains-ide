package com.kirogui.jetbrains.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "KiroGuiSettings", storages = [Storage("kiro-gui.xml")])
class KiroSettings : PersistentStateComponent<KiroSettings.State> {

    class State {
        /** Command or absolute path of the Kiro CLI. IDEs often lack the shell PATH, so absolute is safest. */
        var kiroPath: String = "kiro-cli"
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): KiroSettings = service()
    }
}
