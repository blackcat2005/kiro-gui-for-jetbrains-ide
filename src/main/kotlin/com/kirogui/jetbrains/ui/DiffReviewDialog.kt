package com.kirogui.jetbrains.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.kirogui.jetbrains.acp.PermissionOption
import com.kirogui.jetbrains.kiro.FileChange
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent

/**
 * Shows the change Kiro is about to make as an IDE diff, with the agent's own options
 * (Yes / Always / No) as buttons. Closing the dialog counts as cancelling the tool call.
 */
class DiffReviewDialog(
    private val project: Project,
    private val toolTitle: String,
    private val change: FileChange,
    private val options: List<PermissionOption>,
) : DialogWrapper(project) {

    /** The chosen option id, or null if the dialog was dismissed. */
    var chosen: String? = null
        private set

    private val diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, null)

    init {
        title = "Kiro: $toolTitle"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val type = FileTypeManager.getInstance().getFileTypeByFileName(change.path.fileName.toString())
        val factory = DiffContentFactory.getInstance()
        diffPanel.setRequest(
            SimpleDiffRequest(
                change.path.toString(),
                factory.create(project, change.before, type),
                factory.create(project, change.after, type),
                if (change.isNewFile) "(new file)" else "Current",
                "Proposed by Kiro",
            )
        )
        return diffPanel.component.also { it.preferredSize = Dimension(900, 560) }
    }

    override fun createActions(): Array<Action> = options.map { option ->
        object : DialogWrapperAction(option.name) {
            override fun doAction(e: ActionEvent?) {
                chosen = option.id
                close(OK_EXIT_CODE)
            }
        } as Action
    }.toTypedArray()
}
