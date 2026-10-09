package com.panomc.platform

import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx

/**
 * The tests drive a real server on the same JVM from the JUnit thread, which is not a Vert.x thread.
 *
 * A request started from such a thread gets a fresh context on some event loop, and the callbacks of its futures
 * (`compose { response -> response.body() }`) are queued to that loop. When the response is read on another loop
 * (or the loop is busy, as on a small CI runner) the body can already have arrived, before `body()` attaches its
 * handler: the head is seen, the body is lost and the future never completes. Running the whole request chain
 * inside one context makes every callback run in the turn that produced it, so nothing is lost.
 */
object TestHttp {
    fun <T> onLoop(vertx: Vertx, chain: () -> Future<T>): Future<T> {
        val promise = Promise.promise<T>()

        vertx.getOrCreateContext().runOnContext {
            try {
                chain().onComplete(promise)
            } catch (e: Throwable) {
                promise.fail(e)
            }
        }

        return promise.future()
    }
}
