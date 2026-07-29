package com.aura.providers

import okhttp3.Request
import okhttp3.sse.EventSource

/**
 * Holds an [EventSource] that may not be assigned yet. Used as a
 * cancellation bridge during the brief window between SSE source creation
 * and the first [onEvent] callback. Once the real source is delivered,
 * request()/cancel() delegate to it.
 */
internal class EventSourceHolder : EventSource {
    @Volatile var source: EventSource? = null

    override fun request(): Request = source?.request() ?: Request.Builder().url("http://localhost").build()
    override fun cancel() { source?.cancel() }
}
