package com.kirogui.jetbrains.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ContextHelpLabel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.kirogui.jetbrains.jobs.EvalGuard
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GraphicsEnvironment
import javax.swing.Box
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class KiroConfigurable : Configurable {
    private val pathField = JBTextField()
    private val argsField = JBTextField().apply { emptyText.text = "e.g. --agent my-agent --trust-all-tools" }
    private val envArea = JBTextArea(4, 40).apply { emptyText.text = "KEY=VALUE, one per line" }
    private val shellBox = ComboBox<String>()
    private val shellField = JBTextField().apply { emptyText.text = "e.g. bash -l -c {cmd}" }
    private val jobShellBox = ComboBox<String>()
    private val jobShellField = JBTextField().apply { emptyText.text = "e.g. git-bash -c {cmd}" }
    private val timeoutSpinner = JSpinner(
        SpinnerNumberModel(
            KiroSettings.DEFAULT_START_TIMEOUT, KiroSettings.MIN_START_TIMEOUT, KiroSettings.MAX_START_TIMEOUT, 10,
        ),
    )
    private val engineBox = ComboBox(arrayOf(ENGINE_DEFAULT, "v2", "v3"))
    private val fontBox = ComboBox<String>()
    private val sizeSpinner = JSpinner(SpinnerNumberModel(0, 0, 32, 1))
    private val autoFileBox = JBCheckBox("Attach the open editor file to each prompt")
    private val ideToolsBox = JBCheckBox("Give Kiro the IDE's tools: background commands and the debugger")
    private val evalBox = ComboBox(arrayOf("Safe (block processes, files, network)", "Read-only (plain reads and getters)", "Unrestricted"))
    private val guidanceBox = JBCheckBox("Keep Kiro's steering file about these tools up to date (~/.kiro/steering)")
    private val trustBox = JBCheckBox("Trust all tools: approve Kiro's permission requests without asking")
    private val guidanceProjectBox = JBCheckBox("Also put it in this project's .kiro/steering")
    private val workerModelField = JBTextField().apply { emptyText.text = "blank = a cheap model (haiku)" }
    private val disabledToolsField = JBTextField().apply { emptyText.text = "e.g. debug_eval, bg_run" }
    private val restoreBox = JBCheckBox("Reopen the chats that were open last time")
    private val webChatBox = JBCheckBox("Rounded chat view (embedded browser)")
    private val notifyBox = JBCheckBox("Notify me when Kiro finishes a reply")
    private val backgroundBox = JBCheckBox("Only when the IDE is in the background")

    private var syncing = false

    override fun getDisplayName(): String = "Kiro"

    override fun createComponent(): JComponent {
        val families = listOf(DEFAULT_FONT) + GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
        fontBox.model = DefaultComboBoxModel(families.toTypedArray())
        shellBox.model = DefaultComboBoxModel((KiroLaunch.SHELL_PRESETS.map { it.name } + KiroLaunch.CUSTOM).toTypedArray())
        shellBox.addActionListener {
            if (syncing) return@addActionListener
            val preset = KiroLaunch.SHELL_PRESETS.firstOrNull { it.name == shellBox.selectedItem }
            if (preset != null) shellField.text = preset.template
            shellField.isEnabled = shellBox.selectedItem != KiroLaunch.DIRECT
        }
        jobShellBox.model = DefaultComboBoxModel((KiroLaunch.JOB_SHELL_PRESETS.map { it.name } + KiroLaunch.CUSTOM).toTypedArray())
        jobShellBox.addActionListener {
            if (syncing) return@addActionListener
            val preset = KiroLaunch.JOB_SHELL_PRESETS.firstOrNull { it.name == jobShellBox.selectedItem }
            if (preset != null) jobShellField.text = preset.template
            jobShellField.isEnabled = jobShellBox.selectedItem != KiroLaunch.DEFAULT_SHELL
        }
        notifyBox.addActionListener { backgroundBox.isEnabled = notifyBox.isSelected }
        // Help text lives behind a small "?" next to each setting: hover or click shows it, so the page stays short.
        val help = { c: JComponent, text: String -> withHelp(c, text) }
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("kiro-cli path:"), help(pathField, "Launch settings (path, arguments, shell, environment, engine, timeout) take effect the next time kiro-cli starts: use Restart in the chat header."))
            .addLabeledComponent(JBLabel("Arguments:"), help(argsField, "Added after \"acp\". Quote values that contain spaces. A whole command works too (kiro-cli acp --agent x). The v3 engine refuses --trust-all-tools, --agent, --model, --effort and --trust-tools; with engine v3 the plugin applies --agent and --model after the session opens, treats --trust-all-tools as Kiro's autopilot setting and tells you what it dropped. Pick engine v2 to run them as typed."))
            .addLabeledComponent(JBLabel("Run through:"), help(shellBox, "kiro-cli runs as a plain child process of the IDE, not in the Terminal tool window. Pick a shell to start it from so it sees that shell's PATH and profile. {cmd} stands for the kiro-cli command line."))
            .addLabeledComponent(JBLabel("Shell command:"), shellField)
            .addLabeledComponent(JBLabel("Environment:"), JBScrollPane(envArea))
            .addLabeledComponent(JBLabel("Agent engine:"), help(engineBox, "kiro-cli 3.x has two engines. v3 is the newer one the plugin gets by default; pick v2 to keep the older behaviour. Leave on default unless something misbehaves."))
            .addLabeledComponent(JBLabel("Start timeout (seconds):"), help(timeoutSpinner, "How long to wait for kiro-cli to start and to open a session before giving up. Raise it on machines where security software slows kiro-cli down."))
            .addSeparator(12)
            .addLabeledComponent(JBLabel("Chat font:"), fontBox)
            .addLabeledComponent(JBLabel("Chat font size:"), help(sizeSpinner, "0 = IDE font size + 1. Font changes apply to open chats at once."))
            .addSeparator(12)
            .addComponent(autoFileBox)
            .addComponent(help(restoreBox, "When the project opens, the chat tabs you had open (up to 5) come back with their sessions, instead of one empty chat. Sessions are reloaded in the background, so it can take a few seconds."))
            .addComponent(help(webChatBox, "The chat is drawn in the IDE's embedded browser: rounded cards, a timeline, finer text. Turn off to use the older view if it misbehaves. Applies to chats opened after the change."))
            .addSeparator(12)
            .addComponent(help(ideToolsBox, "Kiro can start slow commands (builds, tests, servers) and debug sessions that run in the IDE while you keep chatting. When one ends or the debugger stops, the plugin sends Kiro the result. Applies to chats opened after the change."))
            .addLabeledComponent(JBLabel("Evaluate expressions in the debugger:"), help(evalBox, "What Kiro's debug_eval may run inside the debugged program. Safe refuses processes, file and network access, reflection and the environment; Read-only also refuses assignments, constructors and calls that are not getters. A best-effort guard, not a sandbox."))
            .addComponent(help(trustBox, "Like --trust-all-tools, which the v3 engine doesn't have: shell commands, file edits (no diff review) and tool calls run without a dialog. Off by default. /trust on|off in the chat does the same until kiro-cli restarts."))
            .addComponent(help(guidanceBox, "An always-on steering file survives Kiro's compaction, so Kiro keeps preferring the IDE's tools. It is only rewritten when it is an older copy of this plugin's own text."))
            .addComponent(guidanceProjectBox)
            .addLabeledComponent(JBLabel("Model for debug_task:"), help(workerModelField, "debug_task hands a debugging investigation to a separate, cheaper Kiro session that uses the debugger and reports back. Model id as in the chat's model list (e.g. claude-haiku-4.5); blank picks a haiku model if there is one, else the current model."))
            .addLabeledComponent(JBLabel("Tools Kiro does not get:"), help(disabledToolsField, "Tool names separated by commas, e.g. debug_eval, bg_run. They disappear from Kiro's tool list at once. Every call Kiro makes is listed under the Jobs panel's Tool calls button."))
            .addLabeledComponent(JBLabel("Shell for Kiro's commands:"), help(jobShellBox, "The shell the commands Kiro starts with bg_run run in. Pick Git Bash if PowerShell is blocked where you work. {cmd} stands for the command; git-bash is found in the usual Git for Windows folders. Applies to the next command. Commands Kiro runs through its own built-in shell tool are not affected: that is kiro-cli's own setting."))
            .addLabeledComponent(JBLabel("Shell command:"), jobShellField)
            .addSeparator(12)
            .addComponent(notifyBox)
            .addComponent(backgroundBox)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    /** [c] with a "?" after it; hovering (or clicking) the "?" shows [text]. */
    private fun withHelp(c: JComponent, text: String): JComponent {
        val q = ContextHelpLabel.create(text)
        return JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            if (c is JBCheckBox) {
                // A check box keeps its natural width; the "?" sits right behind its label.
                add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false; add(c); add(Box.createHorizontalStrut(JBUI.scale(6))); add(q) }, BorderLayout.WEST)
            } else {
                add(c, BorderLayout.CENTER)
                add(q, BorderLayout.EAST)
            }
        }
    }

    private fun state() = KiroSettings.getInstance().state

    private fun chosenEngine(): String = (engineBox.selectedItem as? String)?.takeIf { it != ENGINE_DEFAULT } ?: ""

    private fun chosenEval(): String = EvalGuard.MODES[evalBox.selectedIndex.coerceAtLeast(0)]

    private fun chosenFamily(): String = (fontBox.selectedItem as? String)?.takeIf { it != DEFAULT_FONT } ?: ""

    private fun jobShellText(): String = if (jobShellBox.selectedItem == KiroLaunch.DEFAULT_SHELL) "" else jobShellField.text.trim()

    private fun shellText(): String = if (shellBox.selectedItem == KiroLaunch.DIRECT) "" else shellField.text.trim()

    override fun isModified(): Boolean {
        val s = state()
        return pathField.text.trim() != s.kiroPath || argsField.text.trim() != s.kiroArgs ||
            envArea.text.trim() != s.kiroEnv.trim() || shellText() != s.kiroShell || jobShellText() != s.jobShell || chosenFamily() != s.chatFontFamily ||
            (timeoutSpinner.value as Int) != s.startTimeoutSeconds || chosenEngine() != s.agentEngine ||
            (sizeSpinner.value as Int) != s.chatFontSize ||
            autoFileBox.isSelected != s.autoAttachOpenFile || restoreBox.isSelected != s.restoreChats || webChatBox.isSelected != s.webChat || ideToolsBox.isSelected != s.ideTools || trustBox.isSelected != s.trustAllTools || guidanceBox.isSelected != s.autoGuidance || guidanceProjectBox.isSelected != s.guidanceInProject || workerModelField.text.trim() != s.workerModel || chosenEval() != s.evalMode || disabledToolsField.text.trim() != s.disabledTools || notifyBox.isSelected != s.notifyOnDone || backgroundBox.isSelected != s.notifyOnlyInBackground
    }

    override fun apply() {
        val s = state()
        s.kiroPath = pathField.text.trim().ifEmpty { "kiro-cli" }
        s.kiroArgs = argsField.text.trim()
        s.kiroEnv = envArea.text.trim()
        s.kiroShell = shellText()
        s.jobShell = jobShellText()
        s.startTimeoutSeconds = timeoutSpinner.value as Int
        s.agentEngine = chosenEngine()
        s.chatFontFamily = chosenFamily()
        s.chatFontSize = sizeSpinner.value as Int
        s.autoAttachOpenFile = autoFileBox.isSelected
        s.restoreChats = restoreBox.isSelected
        s.ideTools = ideToolsBox.isSelected
        s.autoGuidance = guidanceBox.isSelected
        s.trustAllTools = trustBox.isSelected
        s.guidanceInProject = guidanceProjectBox.isSelected
        s.workerModel = workerModelField.text.trim()
        s.webChat = webChatBox.isSelected
        s.evalMode = chosenEval()
        s.disabledTools = disabledToolsField.text.trim()
        s.notifyOnDone = notifyBox.isSelected
        s.notifyOnlyInBackground = backgroundBox.isSelected
        KiroSettings.getInstance().notifyChanged()
    }

    override fun reset() {
        val s = state()
        pathField.text = s.kiroPath
        argsField.text = s.kiroArgs
        envArea.text = s.kiroEnv
        engineBox.selectedItem = s.agentEngine.ifBlank { ENGINE_DEFAULT }
        timeoutSpinner.value = s.startTimeoutSeconds.coerceIn(KiroSettings.MIN_START_TIMEOUT, KiroSettings.MAX_START_TIMEOUT)
        syncing = true
        try {
            shellField.text = s.kiroShell
            shellBox.selectedItem = when {
                s.kiroShell.isBlank() -> KiroLaunch.DIRECT
                else -> KiroLaunch.SHELL_PRESETS.firstOrNull { it.template == s.kiroShell }?.name ?: KiroLaunch.CUSTOM
            }
            shellField.isEnabled = shellBox.selectedItem != KiroLaunch.DIRECT
            jobShellField.text = s.jobShell
            jobShellBox.selectedItem = when {
                s.jobShell.isBlank() -> KiroLaunch.DEFAULT_SHELL
                else -> KiroLaunch.JOB_SHELL_PRESETS.firstOrNull { it.template == s.jobShell }?.name ?: KiroLaunch.CUSTOM
            }
            jobShellField.isEnabled = jobShellBox.selectedItem != KiroLaunch.DEFAULT_SHELL
        } finally {
            syncing = false
        }
        fontBox.selectedItem = s.chatFontFamily.ifEmpty { DEFAULT_FONT }
        sizeSpinner.value = s.chatFontSize
        autoFileBox.isSelected = s.autoAttachOpenFile
        restoreBox.isSelected = s.restoreChats
        ideToolsBox.isSelected = s.ideTools
        guidanceBox.isSelected = s.autoGuidance
        trustBox.isSelected = s.trustAllTools
        guidanceProjectBox.isSelected = s.guidanceInProject
        workerModelField.text = s.workerModel
        webChatBox.isSelected = s.webChat
        evalBox.selectedIndex = EvalGuard.MODES.indexOf(s.evalMode).coerceAtLeast(0)
        disabledToolsField.text = s.disabledTools
        notifyBox.isSelected = s.notifyOnDone
        backgroundBox.isSelected = s.notifyOnlyInBackground
        backgroundBox.isEnabled = s.notifyOnDone
    }

    private companion object {
        const val DEFAULT_FONT = "(IDE default)"
        const val ENGINE_DEFAULT = "(kiro-cli default)"
    }
}
