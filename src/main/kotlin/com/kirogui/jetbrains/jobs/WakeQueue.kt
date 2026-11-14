package com.kirogui.jetbrains.jobs

/**
 * Something a chat should hear about: a job ended, the debugger paused. [key] identifies what it is about, so a
 * newer notice replaces an older one with the same key (the latest pause of a debug job, not every pause).
 * [obsolete] is checked at delivery: a pause the user already resumed from is no longer worth a wake-up.
 */
class Notice(
    val sessionId: String,
    val key: String,
    /** One line for the transcript. */
    val summary: String,
    /** What Kiro is told. */
    val text: String,
    val obsolete: () -> Boolean = { false },
)

/**
 * Notices waiting until their chat is idle. A prompt sent while Kiro is in a turn cancels that turn (kiro-cli 3.1),
 * so nothing is sent from here; the chat takes its notices once its own turn has ended.
 */
class WakeQueue {
    private val pending = LinkedHashMap<String, MutableList<Notice>>()

    @Synchronized
    fun post(notice: Notice) {
        val list = pending.getOrPut(notice.sessionId) { mutableListOf() }
        list.removeAll { it.key == notice.key }
        list.add(notice)
    }

    /** Removes and returns the session's notices that are still relevant, oldest first. */
    @Synchronized
    fun take(sessionId: String): List<Notice> = pending.remove(sessionId).orEmpty().filterNot { safeObsolete(it) }

    @Synchronized
    fun has(sessionId: String): Boolean = pending[sessionId]?.any { !safeObsolete(it) } == true

    /** Forgets everything waiting for [sessionId] (a helper session nobody will read). */
    @Synchronized
    fun dropAll(sessionId: String) {
        pending.remove(sessionId)
    }

    @Synchronized
    fun drop(sessionId: String, key: String) {
        pending[sessionId]?.removeAll { it.key == key }
    }

    private fun safeObsolete(n: Notice): Boolean = try {
        n.obsolete()
    } catch (_: Exception) {
        false
    }
}

/** The prompt a chat is woken with. */
object WakePrompt {
    const val TAG = "[kiro-gui]"

    const val FOOTER = "This message was sent automatically by the IDE, not typed by the user. If you were waiting " +
        "for this to continue a task, continue it. Otherwise tell the user briefly what happened and what you suggest next."

    fun compose(notices: List<Notice>): String =
        notices.joinToString("\n\n") { "$TAG ${it.text}" } + "\n\n" + FOOTER
}
