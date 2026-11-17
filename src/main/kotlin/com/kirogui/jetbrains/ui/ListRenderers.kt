package com.kirogui.jetbrains.ui

import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleColoredComponent
import javax.swing.JList
import javax.swing.ListCellRenderer

/** A list renderer that fills a [SimpleColoredComponent] per row (SimpleListCellRenderer.create is deprecated). */
fun <T> listRenderer(fill: (label: SimpleColoredComponent, value: T?) -> Unit): ListCellRenderer<T> =
    object : ColoredListCellRenderer<T>() {
        override fun customizeCellRenderer(list: JList<out T>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) = fill(this, value)
    }
