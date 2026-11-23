package com.panomc.platform.util

import io.vertx.core.http.HttpServerOptions

/**
 * Options for Pano's plain-HTTP listener.
 *
 * Clear-text HTTP/2 (h2c) is off. A client asking to upgrade with a body (`java.net.http` does
 * this by default on every `http://` request, so every node PUT does) makes Vert.x collect the
 * whole body in memory before the upgrade: a 2 GB backup streamed by a node took the heap with it.
 * Nothing needs h2c here: browsers only speak HTTP/2 over TLS, and a reverse proxy in front talks
 * HTTP/1.1 to Pano.
 */
object PlainHttpServerOptions {
    fun create(): HttpServerOptions = HttpServerOptions().setHttp2ClearTextEnabled(false)
}
