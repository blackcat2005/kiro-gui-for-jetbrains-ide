package com.kirogui.jetbrains.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** The plugin's own icons (SVG in /icons), loaded once. */
object KiroIcons {
    @JvmField val Session: Icon = load("/icons/session.svg")
    @JvmField val SessionChild: Icon = load("/icons/session-child.svg")

    private fun load(path: String): Icon = IconLoader.getIcon(path, KiroIcons::class.java)
}
