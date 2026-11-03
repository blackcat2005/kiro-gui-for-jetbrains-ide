package com.kirogui.jetbrains.ui

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.SystemNotifications
import com.kirogui.jetbrains.settings.KiroSettings

/** Tells you a reply is done when you are not watching the chat. */
object KiroNotifier {

    fun turnFinished(project: Project, reply: String?, failed: Boolean) {
        val s = KiroSettings.getInstance().state
        if (!s.notifyOnDone) return
        if (s.notifyOnlyInBackground && watching(project)) return
        val title = if (failed) "Kiro stopped with an error" else "Kiro finished"
        // Outside the IDE too (Windows toast / macOS banner): the platform only shows it while the IDE is not in front.
        SystemNotifications.getInstance().notify("Kiro", title, oneLine(reply).orEmpty())
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Kiro") ?: return
        group.createNotification(title, oneLine(reply).orEmpty(), if (failed) NotificationType.ERROR else NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimple("Open Kiro") {
                ToolWindowManager.getInstance(project).getToolWindow("Kiro")?.activate(null)
            })
            .notify(project)
    }

    /** The IDE window is in front and the Kiro tool window is open, so the reply is already in view. */
    private fun watching(project: Project): Boolean =
        WindowManager.getInstance().getFrame(project)?.isActive == true &&
            ToolWindowManager.getInstance(project).getToolWindow("Kiro")?.isVisible == true

    /** First meaningful line of a Markdown reply, without the markup, cut to [max] characters. */
    fun oneLine(markdown: String?, max: Int = 140): String? {
        var inCode = false
        val line = markdown?.lineSequence()
            ?.filter { raw ->
                if (raw.trim().startsWith("```")) inCode = !inCode
                !inCode && !raw.trim().startsWith("```")
            }
            ?.map { it.trim().trimStart('#', '>', '-', '*', ' ').replace("`", "").replace("**", "").trim() }
            ?.firstOrNull { it.isNotEmpty() }
            ?: return null
        return if (line.length <= max) line else line.take(max - 1).trimEnd() + "…"
    }
}
