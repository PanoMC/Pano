package com.panomc.platform.node

import io.vertx.core.Vertx
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Date

/**
 * The rotating six-digit code an admin reads out of the panel to pair a new node.
 *
 * Deliberately separate from `PlatformCodeManager`, which does the same job for Minecraft servers:
 * the two codes must never be interchangeable, because pairing a node hands the other side the
 * ability to run processes on the host, while pairing a server only links a game world. Sharing
 * one code would mean anyone who saw the server code in a chat log could also register a node.
 *
 * The code rotates every 30 seconds so a code that leaks is useless almost immediately; a pairing
 * attempt only has to hit the code that is current when it arrives.
 *
 * **Pano Agent codes** (`GET /api/panel/servers/agent-link`) work differently (SM-74). An agent
 * code travels with a command somebody copies from the panel and pastes on another machine, or
 * types into the agent's first-run questions, which does not always fit in thirty seconds. So an
 * agent code is minted per request: valid for [AGENT_CODE_TTL_MS], used once, and remembered with
 * the user who asked for it, so the server
 * its agent creates is credited to them. The same user asking again while theirs is still valid
 * gets the same code back. At most [MAX_AGENT_CODES] are alive at once (the oldest go first), no
 * agent code is ever the node code, and no two agent codes are equal: a daemon that pairs with an
 * agent code is approved on the spot and adopts its one server, while one that pairs with the node
 * code waits for an admin, and which of the two a code is must never be in doubt.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class NodePairingCodeManager(
    vertx: Vertx
) {
    /** One agent code: the code, who asked for it, and until when it pairs (epoch ms). */
    data class AgentCode(val code: String, val userId: Long, val expiresAt: Long)

    private val lock = Any()

    /** The live agent codes by code, oldest first. Only touched under [lock]. */
    private val agentCodes = LinkedHashMap<String, AgentCode>()

    @Volatile
    private var pairingCode = generateCode()

    @Volatile
    private var generatedAt = System.currentTimeMillis()

    init {
        vertx.setPeriodic(ROTATE_INTERVAL_MS) {
            rotate()
        }
    }

    fun getPairingCode() = pairingCode

    /** When the current code was generated, so the panel can show how long it is still good for. */
    fun getGeneratedAt() = generatedAt

    /** Whether [candidate] is the code that is valid right now. */
    fun matches(candidate: String?) = isMatch(pairingCode, candidate)

    /**
     * The agent code for [userId]: the one they were given earlier while it is still valid, or a new
     * one valid for [AGENT_CODE_TTL_MS] from [now].
     */
    fun agentCodeFor(userId: Long, now: Long = System.currentTimeMillis()): AgentCode = synchronized(lock) {
        pruneExpired(now)

        agentCodes.values.firstOrNull { it.userId == userId }?.let { return it }

        while (agentCodes.size >= MAX_AGENT_CODES) {
            agentCodes.remove(agentCodes.keys.first())
        }

        var code = generateAgentCode()

        while (code in agentCodes) {
            code = generateAgentCode()
        }

        val minted = AgentCode(code, userId, now + AGENT_CODE_TTL_MS)

        agentCodes[minted.code] = minted

        minted
    }

    /**
     * Takes [candidate] when it is a live agent code, and returns who it was minted for. Taken means
     * gone: an agent code pairs one agent. Null when [candidate] is no such code, or has expired.
     *
     * [acceptAgentLinks] is `managed-servers.accept-agent-links` (SM-77). While it is off nothing
     * pairs as an agent: every live code is dropped -- the switch was turned off some other way than
     * the dialog's, which drops them itself -- and the answer is the one a code that never existed
     * gets, so the caller refuses it exactly like a wrong code.
     */
    fun takeAgentCode(
        candidate: String?,
        now: Long = System.currentTimeMillis(),
        acceptAgentLinks: Boolean = true
    ): AgentCode? = synchronized(lock) {
        if (!acceptAgentLinks) {
            agentCodes.clear()

            return null
        }

        val code = candidate?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val found = agentCodes.remove(code) ?: return null

        found.takeIf { it.expiresAt > now }
    }

    /**
     * Puts back a code [takeAgentCode] handed out for a pairing that then failed on something that
     * was not the code (an unreadable public key), so the admin's command still works when retried.
     */
    fun returnAgentCode(code: AgentCode, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (code.expiresAt > now && code.code !in agentCodes && agentCodes.size < MAX_AGENT_CODES) {
                agentCodes[code.code] = code
            }
        }
    }

    /**
     * Forgets every agent code, so none of them pairs any more (SM-77): what turning
     * `managed-servers.accept-agent-links` off does to the codes the panel already showed.
     * Returns how many there were.
     */
    fun dropAgentCodes(): Int = synchronized(lock) {
        val dropped = agentCodes.size

        agentCodes.clear()

        dropped
    }

    /** How many agent codes can pair right now; for tests. */
    internal fun liveAgentCodes(now: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        pruneExpired(now)

        agentCodes.size
    }

    private fun pruneExpired(now: Long) {
        agentCodes.values.removeIf { it.expiresAt <= now }
    }

    internal fun rotate() {
        synchronized(lock) {
            pruneExpired(System.currentTimeMillis())

            pairingCode = generateCode(agentCodes.keys.mapNotNull { it.toIntOrNull() }.toSet())
        }

        generatedAt = Date().time
    }

    companion object {
        /** How long one node code stays valid, matching the server pairing code's cadence. */
        const val ROTATE_INTERVAL_MS = 30_000L

        /**
         * How long a Pano Agent code pairs: one minute (SM-76). The agent asks for the code at run
         * time, as its last first-run question, and the panel fetches a fresh one the moment the
         * shown one expires, so a minute is enough to copy it -- and a leaked one is soon useless.
         */
        const val AGENT_CODE_TTL_MS = 60_000L

        /** The most agent codes alive at once; a new one pushes the oldest out. */
        const val MAX_AGENT_CODES = 20

        /**
         * How long an agent code is. Unlike the node code nobody reads it out: it travels inside a
         * command that is copied and pasted, so it can be long enough to be unguessable. Twenty of
         * them live at once and every one pairs without an admin's approval, which
         * six digits could not survive against the public rate limit of `/api/node/connect`.
         */
        const val AGENT_CODE_LENGTH = 16

        /**
         * Lower-case letters and digits without the ones that are easy to confuse (0/o, 1/l/i), so
         * a code read off a screen still works, and nothing a shell or a URL would treat specially.
         * 31 symbols over 16 places is about 79 bits.
         */
        private const val AGENT_CODE_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

        private val secureRandom = SecureRandom()

        /**
         * A six-digit code, never starting with a zero so it is always exactly six characters
         * when shown and when compared as text.
         *
         * [SecureRandom] rather than [java.util.Random]: this code is the only thing standing
         * between a stranger on the network and a paired node, so it must not be predictable from
         * previously observed codes.
         */
        internal fun generateCode(random: java.util.Random = secureRandom) = random.nextInt(900000) + 100000

        /**
         * A new agent code: [AGENT_CODE_LENGTH] characters of [AGENT_CODE_ALPHABET]. It always
         * contains letters or is too long to be a node code, so the two kinds can never collide.
         */
        internal fun generateAgentCode(random: java.util.Random = secureRandom): String =
            buildString(AGENT_CODE_LENGTH) {
                repeat(AGENT_CODE_LENGTH) { append(AGENT_CODE_ALPHABET[random.nextInt(AGENT_CODE_ALPHABET.length)]) }
            }

        /** A code that is none of [excluded], so a node code and the agent codes never collide. */
        internal fun generateCode(excluded: Set<Int>, random: java.util.Random = secureRandom): Int {
            var code = generateCode(random)

            while (code in excluded) {
                code = generateCode(random)
            }

            return code
        }

        /**
         * Compares a submitted code with the expected one.
         *
         * Trimmed, because the code travels through a copy-paste field, and compared as text so a
         * value like `000123` can never be coerced into matching.
         */
        internal fun isMatch(expected: Int, candidate: String?): Boolean {
            if (candidate == null) {
                return false
            }

            return candidate.trim() == expected.toString()
        }
    }
}
