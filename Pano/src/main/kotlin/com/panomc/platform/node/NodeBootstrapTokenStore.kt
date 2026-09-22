package com.panomc.platform.node

import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * One-time tokens Pano hands to a node it starts itself.
 *
 * The local node is spawned by Pano on the same machine, so making an admin type a pairing code
 * into a process Pano just launched would be theatre. Instead Pano mints a token, passes it to the
 * child through its environment and accepts exactly one pairing with it.
 *
 * In memory on purpose: a token must not survive the process that issued it, because the only
 * thing it proves is "Pano started me a moment ago". A Pano restart therefore invalidates every
 * outstanding token, which is correct — the child it was minted for died with it.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class NodeBootstrapTokenStore {
    private data class Entry(val expiresAt: Long, val grant: NodeBootstrapGrant)

    private val tokens = ConcurrentHashMap<String, Entry>()

    /**
     * Issues a token that is valid for [TOKEN_TTL_MS] and for exactly one pairing.
     *
     * [grant] is what the node that spends it becomes. It is decided here, at the moment Pano
     * chooses to trust a pairing, rather than claimed by the daemon in its pairing request: a
     * node that could name its own kind could call itself local and skip the approval step.
     */
    fun issue(
        grant: NodeBootstrapGrant = NodeBootstrapGrant.LOCAL_NODE,
        now: Long = System.currentTimeMillis()
    ): String {
        purgeExpired(now)

        val bytes = ByteArray(TOKEN_BYTES)

        secureRandom.nextBytes(bytes)

        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        tokens[token] = Entry(now + TOKEN_TTL_MS, grant)

        return token
    }

    /**
     * Spends [token] and returns what it grants, or null when it was not valid.
     *
     * The removal happens before the expiry check, so a token can never be spent twice even if two
     * pairings race: [ConcurrentHashMap.remove] returns the value to exactly one caller.
     */
    fun consume(token: String?, now: Long = System.currentTimeMillis()): NodeBootstrapGrant? {
        purgeExpired(now)

        if (token.isNullOrBlank()) {
            return null
        }

        val entry = tokens.remove(token) ?: return null

        return if (entry.expiresAt > now) entry.grant else null
    }

    /** Drops a token that was issued for a node launch that never happened. */
    fun revoke(token: String?) {
        if (token == null) {
            return
        }

        tokens.remove(token)
    }

    /** Outstanding, unexpired tokens. Exposed for tests and for the local-node status card. */
    fun size(now: Long = System.currentTimeMillis()): Int {
        purgeExpired(now)

        return tokens.size
    }

    private fun purgeExpired(now: Long) {
        tokens.entries.removeIf { it.value.expiresAt <= now }
    }

    companion object {
        /**
         * How long a bootstrap token stays usable. Long enough for a node to download, unpack and
         * start on a slow host, short enough that a token left in a process environment is not a
         * standing invitation.
         */
        const val TOKEN_TTL_MS = 10 * 60 * 1000L

        private const val TOKEN_BYTES = 32

        private val secureRandom = SecureRandom()
    }
}
