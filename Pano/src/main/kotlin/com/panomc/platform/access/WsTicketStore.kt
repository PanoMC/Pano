package com.panomc.platform.access

import com.panomc.platform.auth.CredentialSource
import com.panomc.platform.model.Error
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** `?ticket=` on the WebSocket upgrade is unknown, already used or older than 30 seconds: `401 INVALID_WS_TICKET`. */
class InvalidWsTicket : Error("INVALID_WS_TICKET", 401, "Unauthorized")

/**
 * One-time WebSocket tickets (open front-end plan, doc 05 §6). A browser cannot set an `Authorization` header on a
 * WebSocket and must not rely on the ambient cookie from another origin, so a logged-in caller (a browser, or a
 * server-side front-end for its visitor) first asks `POST /auth/ws-ticket` and then opens `/ws?ticket=...`.
 *
 * In memory only: a restart drops every ticket, which is fine for a 30 second credential. A ticket is **single use**
 * ([consume] removes it) and holds only the user id. The primary constructor takes the clock so tests need no sleeping.
 */
@Lazy
@Component
class WsTicketStore internal constructor(private val now: () -> Long, private val ttlMillis: Long) {
    @Autowired
    constructor() : this({ System.currentTimeMillis() }, TTL_SECONDS * 1000L)

    private class Entry(val userId: Long, val expiresAt: Long)

    private val tickets = ConcurrentHashMap<String, Entry>()

    private val random = SecureRandom()

    /** A new ticket for [userId], valid for [TTL_SECONDS]. */
    fun issue(userId: Long): String {
        val time = now()

        if (tickets.size >= MAX_TICKETS) {
            purge(time)
        }

        // Still full of live tickets: the oldest go first, so one noisy caller cannot grow the map without bound.
        while (tickets.size >= MAX_TICKETS) {
            val oldest = tickets.entries.minByOrNull { it.value.expiresAt } ?: break

            tickets.remove(oldest.key)
        }

        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        tickets[ticket] = Entry(userId, time + ttlMillis)

        return ticket
    }

    /** The user id a live [ticket] was issued for, **removing it**; `null` when it is unknown, used or expired. */
    fun consume(ticket: String?): Long? {
        if (ticket.isNullOrEmpty()) return null

        val entry = tickets.remove(ticket) ?: return null

        return if (entry.expiresAt > now()) entry.userId else null
    }

    /** Tickets currently held (expired ones included until the next [purge]); for tests. */
    internal fun size(): Int = tickets.size

    private fun purge(time: Long) {
        tickets.entries.removeIf { it.value.expiresAt <= time }
    }

    companion object {
        const val TTL_SECONDS = 30L

        /** The routing-context key the upgrade handler reads the resolved user id from. */
        const val USER_ID_KEY = "pano.ws.userId"

        private const val MAX_TICKETS = 10_000

        /**
         * Whether a cookie-authenticated upgrade may proceed (doc 05 §6): the browser page must be on the site's own
         * origin ([OriginClass.SAME]) or, for the website socket, on an allowed one. A request without an `Origin`
         * header cannot come from a page, so it is not a cross-site hijack and passes. The panel socket is the panel's
         * own: [panel] narrows it to the site itself.
         */
        fun cookieUpgradeAllowed(origin: OriginClass, panel: Boolean): Boolean = when (origin) {
            OriginClass.NONE, OriginClass.SAME -> true
            OriginClass.ALLOWED -> !panel
            OriginClass.FOREIGN -> false
        }
    }
}

/**
 * Who opens a WebSocket (doc 05 §6), shared by the website socket and the panel socket and free of any endpoint
 * so it can be tested on its own.
 */
object WsAuth {
    /**
     * The user id of a website-socket upgrade, in this order:
     * 1. a `?ticket=` ([ticket] is not null): [beforeTicket] runs the setup / demo / maintenance checks, then the
     *    ticket is consumed; unknown, used or old is [InvalidWsTicket]. No Origin check, a ticket is not ambient;
     * 2. an `Authorization: Bearer` ([source] is `BEARER`): not ambient either, no Origin check;
     * 3. the session cookie ([source] is `COOKIE`): the page's [origin] must pass
     *    [WsTicketStore.cookieUpgradeAllowed], else [OriginNotAllowed].
     *
     * Steps 2 and 3 resolve the user through [session] (the endpoint's usual login checks).
     */
    suspend fun resolveUser(
        ticket: String?,
        source: CredentialSource?,
        origin: OriginClass,
        originHeader: String?,
        store: WsTicketStore,
        beforeTicket: suspend () -> Unit,
        session: suspend () -> Long
    ): Long {
        if (ticket != null) {
            beforeTicket()

            return store.consume(ticket) ?: throw InvalidWsTicket()
        }

        checkCookieOrigin(source, origin, originHeader, panel = false)

        return session()
    }

    /** Refuses a cookie-authenticated upgrade from a page that may not use the cookie ([OriginNotAllowed]). */
    fun checkCookieOrigin(source: CredentialSource?, origin: OriginClass, originHeader: String?, panel: Boolean) {
        if (source == CredentialSource.COOKIE && !WsTicketStore.cookieUpgradeAllowed(origin, panel)) {
            throw OriginNotAllowed(originHeader)
        }
    }
}
