package com.kirogui.jetbrains.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.io.PrintWriter
import java.io.StringWriter
import javax.swing.JPanel

class KiroToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val disposable = Disposer.newDisposable(toolWindow.disposable, "KiroShell")
        // One content: the chats are tabs inside it, next to the shared session list and jobs.
        val component = try {
            KiroShell(project, disposable)
        } catch (e: Throwable) {
            // Without this the tool window stays empty ("Nothing to show") and nobody can tell why.
            Logger.getInstance(KiroToolWindowFactory::class.java).error("Kiro tool window failed to start", e)
            failedPanel(e)
        }
        val content = ContentFactory.getInstance().createContent(component, "", false)
        content.isCloseable = false
        content.setDisposer(disposable)
        toolWindow.contentManager.addContent(content)
    }

    private fun failedPanel(e: Throwable): JPanel {
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        val text = JBTextArea(
            "Kiro could not start its panel.\n\n" +
                "Please report this at the plugin's issue tracker and include the text below " +
                "(also written to idea.log: Help | Show Log in Explorer/Finder).\n\n$trace",
        ).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(8)
        }
        return JPanel(BorderLayout()).apply { add(JBScrollPane(text), BorderLayout.CENTER) }
    }
}
