package com.panomc.node.net

/**
 * Whether one socket to Pano is still carrying anything, judged from the node's side.
 *
 * Only arrivals count. A write through a dead tunnel succeeds: the bytes go into a kernel buffer, or
 * to an ngrok or Cloudflare edge that is still up while the Pano behind it is gone, and nothing
 * ever comes back. So every inbound frame is [heard] -- a message, Pano's own ping, or the pong
 * to the node's -- and a socket that has heard nothing for [timeoutMillis] is given up.
 *
 * One instance per socket, created when it opens: a late frame from a socket that was already
 * replaced cannot make the new one look alive, and a new socket starts with a full timeout rather
 * than whatever silence its predecessor had built up.
 *
 * [clock] is injected only so the rule can be tested without sleeping.
 */
class ConnectionLiveness(
    val timeoutMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** What the watchdog does on one tick. */
    enum class Verdict {
        /** Heard from recently enough: ask again, so the next tick has an answer to go by. */
        PING,

        /** Silent for the whole timeout: close the socket and reconnect. */
        GIVE_UP
    }

    @Volatile
    private var lastInboundAt = clock()

    /** Something arrived on the socket. */
    fun heard() {
        lastInboundAt = clock()
    }

    /** How long nothing has arrived, in milliseconds. */
    fun silenceMillis(): Long = clock() - lastInboundAt

    fun check(): Verdict = if (silenceMillis() >= timeoutMillis) Verdict.GIVE_UP else Verdict.PING
}
