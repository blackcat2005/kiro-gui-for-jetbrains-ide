package com.kirogui.jetbrains.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
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
    private val fontBox = ComboBox<String>()
    private val sizeSpinner = JSpinner(SpinnerNumberModel(0, 0, 32, 1))

    override fun getDisplayName(): String = "Kiro"

    override fun createComponent(): JComponent {
        val families = listOf(DEFAULT_FONT) + GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
        fontBox.model = DefaultComboBoxModel(families.toTypedArray())
        val hint = { text: String ->
            JBLabel(text).apply {
                foreground = UIUtil.getContextHelpForeground()
                font = JBUI.Fonts.smallFont()
            }
        }
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("kiro-cli path:"), pathField)
            .addLabeledComponent(JBLabel("Arguments:"), argsField)
            .addTooltip("Added after \"acp\". Quote values that contain spaces.")
            .addLabeledComponent(JBLabel("Environment:"), JBScrollPane(envArea))
            .addSeparator(12)
            .addLabeledComponent(JBLabel("Chat font:"), fontBox)
            .addLabeledComponent(JBLabel("Chat font size:"), sizeSpinner)
            .addTooltip("0 = IDE font size + 1. Font changes apply to open chats at once.")
            .addComponent(hint("Arguments and environment take effect the next time kiro-cli starts (Restart in the chat header)."))
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    private fun state() = KiroSettings.getInstance().state

    private fun chosenFamily(): String = (fontBox.selectedItem as? String)?.takeIf { it != DEFAULT_FONT } ?: ""

    override fun isModified(): Boolean {
        val s = state()
        return pathField.text.trim() != s.kiroPath || argsField.text.trim() != s.kiroArgs ||
            envArea.text.trim() != s.kiroEnv.trim() || chosenFamily() != s.chatFontFamily ||
            (sizeSpinner.value as Int) != s.chatFontSize
    }

    override fun apply() {
        val s = state()
        s.kiroPath = pathField.text.trim().ifEmpty { "kiro-cli" }
        s.kiroArgs = argsField.text.trim()
        s.kiroEnv = envArea.text.trim()
        s.chatFontFamily = chosenFamily()
        s.chatFontSize = sizeSpinner.value as Int
        KiroSettings.getInstance().notifyChanged()
    }

    override fun reset() {
        val s = state()
        pathField.text = s.kiroPath
        argsField.text = s.kiroArgs
        envArea.text = s.kiroEnv
        fontBox.selectedItem = s.chatFontFamily.ifEmpty { DEFAULT_FONT }
        sizeSpinner.value = s.chatFontSize
    }

    private companion object {
        const val DEFAULT_FONT = "(IDE default)"
    }
}
