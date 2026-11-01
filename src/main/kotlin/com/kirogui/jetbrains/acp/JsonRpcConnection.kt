package com.kirogui.jetbrains.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class JsonRpcException(val code: Int, message: String) : RuntimeException(message) {
    companion object {
        const val METHOD_NOT_FOUND = -32601
        const val INTERNAL_ERROR = -32603
    }
}

/**
 * Newline-delimited JSON-RPC 2.0 over a pair of streams (the framing ACP uses over stdio).
 *
 * - Notifications are delivered inline on the reader thread, so their order is preserved
 *   (important for streamed message chunks).
 * - Incoming requests are handled on worker threads, so a handler may block (e.g. waiting for
 *   the user to answer a permission prompt) without stalling the reader.
 */
class JsonRpcConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val handler: Handler,
) : AutoCloseable {

    interface Handler {
        /** Return the `result` value, or throw [JsonRpcException] to send an error response. */
        fun onRequest(method: String, params: JsonElement?): JsonElement
        fun onNotification(method: String, params: JsonElement?)
        fun onClosed(cause: Throwable?)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonElement>>()
    private val writeLock = Any()
    private val workers = Executors.newCachedThreadPool { r ->
        Thread(r, "acp-worker").apply { isDaemon = true }
    }

    @Volatile
    private var closed = false

    fun start() {
        Thread({ readLoop() }, "acp-reader").apply { isDaemon = true }.start()
    }

    fun request(method: String, params: JsonElement? = null): CompletableFuture<JsonElement> {
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<JsonElement>()
        pending[id] = future
        if (closed) {
            pending.remove(id)
            future.completeExceptionally(IOException("Connection closed"))
            return future
        }
        try {
            send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                if (params != null) put("params", params)
            })
        } catch (e: IOException) {
            pending.remove(id)
            future.completeExceptionally(e)
        }
        return future
    }

    fun notify(method: String, params: JsonElement? = null) {
        try {
            send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                if (params != null) put("params", params)
            })
        } catch (_: IOException) {
            // connection is gone; onClosed already reports it
        }
    }

    private fun send(message: JsonObject) {
        val line = json.encodeToString(JsonObject.serializer(), message)
        synchronized(writeLock) {
            output.write((line + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    private fun readLoop() {
        var cause: Throwable? = null
        try {
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val msg = try {
                    json.parseToJsonElement(line).jsonObject
                } catch (_: Exception) {
                    continue // non-JSON noise on stdout; ignore
                }
                dispatch(msg)
            }
        } catch (e: Exception) {
            cause = e
        } finally {
            closed = true
            val err = IOException("Connection closed", cause)
            pending.values.forEach { it.completeExceptionally(err) }
            pending.clear()
            workers.shutdown()
            handler.onClosed(cause)
        }
    }

    private fun dispatch(msg: JsonObject) {
        val method = msg["method"]?.jsonPrimitive?.contentOrNull
        val id = msg["id"]?.takeUnless { it is JsonNull }
        when {
            method != null && id != null -> workers.execute { respond(id, method, msg["params"]) }
            method != null -> handler.onNotification(method, msg["params"])
            id != null -> {
                val future = id.jsonPrimitive.longOrNull?.let { pending.remove(it) } ?: return
                val error = msg["error"]?.takeUnless { it is JsonNull }?.jsonObject
                if (error != null) {
                    future.completeExceptionally(
                        JsonRpcException(
                            error["code"]?.jsonPrimitive?.intOrNull ?: JsonRpcException.INTERNAL_ERROR,
                            error["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error",
                        )
                    )
                } else {
                    future.complete(msg["result"] ?: JsonNull)
                }
            }
        }
    }

    private fun respond(id: JsonElement, method: String, params: JsonElement?) {
        val reply = try {
            val result = handler.onRequest(method, params)
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            }
        } catch (e: JsonRpcException) {
            errorReply(id, e.code, e.message ?: "Error")
        } catch (e: Exception) {
            errorReply(id, JsonRpcException.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName)
        }
        try {
            send(reply)
        } catch (_: IOException) {
        }
    }

    private fun errorReply(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    override fun close() {
        closed = true
        try {
            output.close()
        } catch (_: IOException) {
        }
        try {
            input.close()
        } catch (_: IOException) {
        }
        workers.shutdownNow()
    }
}
