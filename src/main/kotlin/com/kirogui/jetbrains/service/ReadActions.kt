package com.kirogui.jetbrains.service

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.ThrowableComputable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Runs [block] under a read action. The Kotlin `runReadAction` extension is deprecated, and so is `ReadAction.compute` in
 * newer IDEs in favour of `computeBlocking`, which 251 does not have yet; whichever exists is looked up once by name.
 */
fun <T> readNow(block: () -> T): T {
    try {
        @Suppress("UNCHECKED_CAST")
        return READ.invoke(null, ThrowableComputable<T, RuntimeException> { block() }) as T
    } catch (e: InvocationTargetException) {
        throw e.targetException ?: e
    }
}

private val READ: Method = ReadAction::class.java.let { c ->
    try {
        c.getMethod("computeBlocking", ThrowableComputable::class.java)
    } catch (_: NoSuchMethodException) {
        c.getMethod("compute", ThrowableComputable::class.java)
    }
}
