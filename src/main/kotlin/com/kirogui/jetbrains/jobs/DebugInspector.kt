package com.kirogui.jetbrains.jobs

import com.kirogui.jetbrains.service.readNow
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueContainer
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Icon

/** A stack frame as text. [location] is `path:line` (1-based). */
data class FrameLine(val label: String, val location: String?)

/** A variable as text. */
data class VarLine(val name: String, val type: String?, val value: String, val hasChildren: Boolean = false)

/** How a paused program is described to Kiro. Pure text, so it is testable without a debugger. */
object DebugText {
    const val MAX_VALUE = 300

    fun value(v: VarLine): String {
        val flat = v.value.replace("\r", "").replace('\n', ' ').trim()
        val shown = if (flat.length > MAX_VALUE) flat.take(MAX_VALUE - 1) + "…" else flat
        return v.name + (v.type?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + " = " + shown
    }

    fun snapshot(
        header: String,
        source: String?,
        thread: String?,
        frames: List<FrameLine>,
        selected: Int,
        variables: List<VarLine>,
        variablesNote: String?,
        console: String? = null,
    ): String = buildString {
        append(header)
        if (!source.isNullOrBlank()) append('\n').append(source)
        if (frames.isNotEmpty()) {
            append("\nStack").append(thread?.let { " (thread \"$it\")" } ?: "").append(':')
            frames.forEachIndexed { i, f ->
                append("\n  #").append(i).append(' ').append(f.label)
                if (f.location != null && !f.label.contains(f.location.substringAfterLast('/'))) append("  [").append(f.location).append(']')
                if (i == selected && frames.size > 1) append("   <- selected")
            }
        }
        append("\nVariables in frame #").append(selected).append(':')
        if (variables.isEmpty()) append(" ").append(variablesNote ?: "(none)")
        else {
            variables.forEach { append("\n  ").append(value(it)) }
            variablesNote?.let { append("\n  ").append(it) }
        }
        if (!console.isNullOrBlank()) append('\n').append(console)
    }
}

/**
 * Reads a paused debug session through the XDebugger API, which is callback-based and asynchronous everywhere
 * (frames, children, value presentations). Each read has a deadline, so a slow debugger can't hang a tool call.
 */
internal object DebugInspector {

    class Frame(val index: Int, val frame: XStackFrame, val label: String)

    fun frames(session: XDebugSession, max: Int, timeoutMs: Long): List<Frame> {
        val stack = session.suspendContext?.activeExecutionStack ?: return emptyList()
        val found = CopyOnWriteArrayList<XStackFrame>()
        stack.topFrame?.let { found.add(it) }
        val done = CompletableFuture<Unit>()
        if (found.size < max) {
            // Like the Frames view: the top frame comes from getTopFrame(), the rest are computed from index 1.
            stack.computeStackFrames(found.size, object : XExecutionStack.XStackFrameContainer {
                override fun addStackFrames(stackFrames: MutableList<out XStackFrame>, last: Boolean) {
                    found.addAll(stackFrames)
                    if (last || found.size >= max) done.complete(Unit)
                }

                override fun errorOccurred(errorMessage: String) {
                    done.complete(Unit)
                }

                override fun isObsolete(): Boolean = done.isDone
            })
            await(done, timeoutMs)
        }
        return found.take(max).mapIndexed { i, f -> Frame(i, f, label(f)) }
    }

    fun threadName(session: XDebugSession): String? = try {
        session.suspendContext?.activeExecutionStack?.displayName
    } catch (_: Exception) {
        null
    }

    fun label(frame: XStackFrame): String {
        val text = StringBuilder()
        try {
            frame.customizePresentation(object : ColoredTextContainer {
                override fun append(fragment: String, attributes: SimpleTextAttributes) {
                    text.append(fragment)
                }
            })
        } catch (_: Exception) {
        }
        return text.toString().trim().ifEmpty {
            frame.sourcePosition?.let { "${it.file.name}:${it.line + 1}" } ?: "<frame>"
        }
    }

    /** Names and values of [container]'s children, at most [max]. The second value says why the list is short. */
    fun children(container: XValueContainer, max: Int, timeoutMs: Long): Pair<List<Pair<String, XValue>>, String?> {
        val out = CopyOnWriteArrayList<Pair<String, XValue>>()
        val note = AtomicReference<String?>(null)
        val done = CompletableFuture<Unit>()
        container.computeChildren(object : XCompositeNode {
            override fun addChildren(children: XValueChildrenList, last: Boolean) {
                for (i in 0 until children.size()) out.add(children.getName(i) to children.getValue(i))
                if (out.size >= max) note.compareAndSet(null, "(more not shown)")
                if (last || out.size >= max) done.complete(Unit)
            }

            // 251 still declares the one-argument form abstract, so both are implemented (the newer IDEs call the second).
            @Deprecated("Replaced by the two-argument form")
            override fun tooManyChildren(remaining: Int) = tooManyChildren(remaining, Runnable {})

            override fun tooManyChildren(remaining: Int, addNextChildren: Runnable) {
                note.compareAndSet(null, "($remaining more not shown)")
                done.complete(Unit)
            }

            override fun setAlreadySorted(alreadySorted: Boolean) {}

            override fun setErrorMessage(errorMessage: String) {
                note.compareAndSet(null, "(error: $errorMessage)")
                done.complete(Unit)
            }

            override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) = setErrorMessage(errorMessage)

            override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}

            override fun isObsolete(): Boolean = done.isDone
        })
        if (!await(done, timeoutMs)) note.compareAndSet(null, "(the debugger did not list everything in time)")
        return out.take(max) to note.get()
    }

    /** Presentations of many values at once, all requested up front and collected until [deadline]. */
    fun present(values: List<Pair<String, XValue>>, deadline: Long): List<VarLine> {
        val pending = values.map { (name, value) -> name to presentAsync(value) }
        return pending.map { (name, future) ->
            val left = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
            val p = try {
                future.get(left, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                future.getNow(null)
            }
            VarLine(name, p?.type, p?.value ?: "(not computed in time)", p?.hasChildren == true)
        }
    }

    class Presentation(val type: String?, val value: String, val hasChildren: Boolean)

    /**
     * Completes with the value's text. Java shows "Collecting data…" first and the real text (e.g. `toString()`)
     * a moment later, so a placeholder only counts if nothing better arrives.
     */
    private class PresentationFuture : CompletableFuture<Presentation>() {
        @Volatile var latest: Presentation? = null

        override fun getNow(valueIfAbsent: Presentation?): Presentation? = super.getNow(null) ?: latest ?: valueIfAbsent
    }

    private fun presentAsync(value: XValue): CompletableFuture<Presentation> {
        val future = PresentationFuture()
        fun offer(p: Presentation) {
            future.latest = p
            if (!p.value.contains("Collecting data")) future.complete(p)
        }
        try {
            value.computePresentation(object : XValueNode {
                override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) =
                    offer(Presentation(type, value, hasChildren))

                override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) =
                    offer(Presentation(presentation.type, render(presentation), hasChildren))

                override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}

                override fun isObsolete(): Boolean = future.isDone
            }, XValuePlace.TREE)
        } catch (e: Exception) {
            future.complete(Presentation(null, "(error: ${e.message})", false))
        }
        return future
    }

    fun render(p: XValuePresentation): String {
        val text = StringBuilder()
        p.renderValue(object : XValuePresentation.XValueTextRenderer {
            override fun renderValue(value: String) {
                text.append(value)
            }

            override fun renderStringValue(value: String) {
                text.append('"').append(value).append('"')
            }

            override fun renderNumericValue(value: String) {
                text.append(value)
            }

            override fun renderKeywordValue(value: String) {
                text.append(value)
            }

            override fun renderValue(value: String, key: TextAttributesKey) {
                text.append(value)
            }

            override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) {
                val shown = if (maxLength in 1 until value.length) value.take(maxLength) + "…" else value
                text.append('"').append(shown).append('"')
            }

            override fun renderComment(comment: String) {
                text.append(comment)
            }

            override fun renderSpecialSymbol(symbol: String) {
                text.append(symbol)
            }

            override fun renderError(error: String) {
                text.append(error)
            }
        })
        return text.toString()
    }

    fun variables(frame: XStackFrame, max: Int, timeoutMs: Long): Pair<List<VarLine>, String?> {
        val deadline = System.currentTimeMillis() + timeoutMs
        val (children, note) = children(frame, max, timeoutMs / 2)
        return present(children, deadline) to note
    }

    /** Evaluates [expression] in [frame] (or wherever the session stands). Throws with the debugger's message. */
    fun evaluate(session: XDebugSession, frame: XStackFrame?, expression: String, timeoutMs: Long): XValue {
        val evaluator = frame?.evaluator ?: session.debugProcess.evaluator
            ?: throw IllegalStateException("This debugger can't evaluate expressions.")
        val answer = CompletableFuture<XValue>()
        evaluator.evaluate(expression, object : XDebuggerEvaluator.XEvaluationCallback {
            override fun evaluated(result: XValue) {
                answer.complete(result)
            }

            override fun errorOccurred(errorMessage: String) {
                answer.completeExceptionally(IllegalStateException(errorMessage))
            }
        }, frame?.sourcePosition ?: session.currentPosition)
        return try {
            answer.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (_: TimeoutException) {
            throw IllegalStateException("The debugger did not answer within ${timeoutMs / 1000} s.")
        }
    }

    /** Lines around [line] (0-based) with the current one marked. */
    fun sourceLines(file: VirtualFile, line: Int, around: Int): String? = try {
        readNow {
            val doc = FileDocumentManager.getInstance().getDocument(file) ?: return@readNow null
            if (line !in 0 until doc.lineCount) return@readNow null
            val from = (line - around).coerceAtLeast(0)
            val to = (line + around).coerceAtMost(doc.lineCount - 1)
            (from..to).joinToString("\n") { i ->
                val text = doc.getText(TextRange(doc.getLineStartOffset(i), doc.getLineEndOffset(i)))
                (if (i == line) "-> " else "   ") + "%4d | ".format(i + 1) + text
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun await(future: CompletableFuture<*>, timeoutMs: Long): Boolean = try {
        future.get(timeoutMs, TimeUnit.MILLISECONDS)
        true
    } catch (_: Exception) {
        false
    }
}
