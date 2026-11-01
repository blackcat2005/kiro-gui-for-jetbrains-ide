package com.kirogui.jetbrains.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

class KiroConfigurable : Configurable {
    private val pathField = JBTextField()

    override fun getDisplayName(): String = "Kiro"

    override fun createComponent(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent(JBLabel("kiro-cli path:"), pathField)
        .addComponentFillVertically(javax.swing.JPanel(), 0)
        .panel

    override fun isModified(): Boolean = pathField.text.trim() != KiroSettings.getInstance().state.kiroPath

    override fun apply() {
        KiroSettings.getInstance().state.kiroPath = pathField.text.trim().ifEmpty { "kiro-cli" }
    }

    override fun reset() {
        pathField.text = KiroSettings.getInstance().state.kiroPath
    }
}
