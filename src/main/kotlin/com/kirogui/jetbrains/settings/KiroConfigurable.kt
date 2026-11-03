package com.kirogui.jetbrains.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.GraphicsEnvironment
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
    private val fontBox = ComboBox<String>()
    private val sizeSpinner = JSpinner(SpinnerNumberModel(0, 0, 32, 1))
    private val autoFileBox = JBCheckBox("Attach the open editor file to each prompt")
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
        notifyBox.addActionListener { backgroundBox.isEnabled = notifyBox.isSelected }
        // Hints wrap at a fixed width: one long unwrapped line would make the whole page wider than the window.
        val hint = { text: String ->
            JBLabel("<html><body style='width: ${JBUI.scale(HINT_WIDTH)}px'>$text</body></html>").apply {
                foreground = UIUtil.getContextHelpForeground()
                font = JBUI.Fonts.smallFont()
            }
        }
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("kiro-cli path:"), pathField)
            .addLabeledComponent(JBLabel("Arguments:"), argsField)
            .addComponent(hint("Added after \"acp\". Quote values that contain spaces."), 0)
            .addLabeledComponent(JBLabel("Run through:"), shellBox)
            .addLabeledComponent(JBLabel("Shell command:"), shellField)
            .addComponent(hint("kiro-cli runs as a plain child process of the IDE, not in the Terminal tool window. Pick a shell to start it from so it sees that shell's PATH and profile. {cmd} stands for the kiro-cli command line."), 0)
            .addLabeledComponent(JBLabel("Environment:"), JBScrollPane(envArea))
            .addComponent(hint("Launch settings take effect the next time kiro-cli starts (Restart in the chat header)."))
            .addSeparator(12)
            .addLabeledComponent(JBLabel("Chat font:"), fontBox)
            .addLabeledComponent(JBLabel("Chat font size:"), sizeSpinner)
            .addComponent(hint("0 = IDE font size + 1. Font changes apply to open chats at once."), 0)
            .addSeparator(12)
            .addComponent(autoFileBox)
            .addSeparator(12)
            .addComponent(notifyBox)
            .addComponent(backgroundBox)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    private fun state() = KiroSettings.getInstance().state

    private fun chosenFamily(): String = (fontBox.selectedItem as? String)?.takeIf { it != DEFAULT_FONT } ?: ""

    private fun shellText(): String = if (shellBox.selectedItem == KiroLaunch.DIRECT) "" else shellField.text.trim()

    override fun isModified(): Boolean {
        val s = state()
        return pathField.text.trim() != s.kiroPath || argsField.text.trim() != s.kiroArgs ||
            envArea.text.trim() != s.kiroEnv.trim() || shellText() != s.kiroShell || chosenFamily() != s.chatFontFamily ||
            (sizeSpinner.value as Int) != s.chatFontSize ||
            autoFileBox.isSelected != s.autoAttachOpenFile || notifyBox.isSelected != s.notifyOnDone || backgroundBox.isSelected != s.notifyOnlyInBackground
    }

    override fun apply() {
        val s = state()
        s.kiroPath = pathField.text.trim().ifEmpty { "kiro-cli" }
        s.kiroArgs = argsField.text.trim()
        s.kiroEnv = envArea.text.trim()
        s.kiroShell = shellText()
        s.chatFontFamily = chosenFamily()
        s.chatFontSize = sizeSpinner.value as Int
        s.autoAttachOpenFile = autoFileBox.isSelected
        s.notifyOnDone = notifyBox.isSelected
        s.notifyOnlyInBackground = backgroundBox.isSelected
        KiroSettings.getInstance().notifyChanged()
    }

    override fun reset() {
        val s = state()
        pathField.text = s.kiroPath
        argsField.text = s.kiroArgs
        envArea.text = s.kiroEnv
        syncing = true
        try {
            shellField.text = s.kiroShell
            shellBox.selectedItem = when {
                s.kiroShell.isBlank() -> KiroLaunch.DIRECT
                else -> KiroLaunch.SHELL_PRESETS.firstOrNull { it.template == s.kiroShell }?.name ?: KiroLaunch.CUSTOM
            }
            shellField.isEnabled = shellBox.selectedItem != KiroLaunch.DIRECT
        } finally {
            syncing = false
        }
        fontBox.selectedItem = s.chatFontFamily.ifEmpty { DEFAULT_FONT }
        sizeSpinner.value = s.chatFontSize
        autoFileBox.isSelected = s.autoAttachOpenFile
        notifyBox.isSelected = s.notifyOnDone
        backgroundBox.isSelected = s.notifyOnlyInBackground
        backgroundBox.isEnabled = s.notifyOnDone
    }

    private companion object {
        const val DEFAULT_FONT = "(IDE default)"
        const val HINT_WIDTH = 420
    }
}
