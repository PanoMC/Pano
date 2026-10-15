package com.panomc.platform.access

import com.panomc.platform.db.dao.FrontendKeyDao
import com.panomc.platform.db.model.FrontendKey
import com.panomc.platform.model.Error
import io.vertx.core.http.HttpServerRequest
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** The panel already holds [FrontendKeyService.MAX_KEYS] keys; revoke one first. */
class FrontendKeyLimitReached : Error("FRONTEND_KEY_LIMIT_REACHED", 400)

/** Who a matched key is: the stored row's [id] and [name], or the per-boot internal key (id 0). */
data class FrontendKeyRef(val id: Long, val name: String) {
    /** The internal key of this boot (never stored); sessions stay cookie sessions for it. */
    val isInternal: Boolean get() = id == INTERNAL_ID

    companion object {
        const val INTERNAL_ID = 0L
    }
}

/** What a request says about the front-end key (doc 05 §3.2). */
sealed interface KeyMatch {
    /** No `X-Pano-Frontend-Key` header, or an empty one: an ordinary (anonymous or cookie) request. */
    data object None : KeyMatch

    data class Valid(val ref: FrontendKeyRef) : KeyMatch

    /** A key was sent and is unknown or revoked: `401 INVALID_FRONTEND_KEY`, never treated as anonymous. */
    data object Invalid : KeyMatch
}

/** A freshly created key. [key] exists only here: Pano keeps the hash. */
data class CreatedFrontendKey(val id: Long, val name: String, val key: String)

/**
 * Front-end keys (open front-end plan, doc 05 §3): create, list, revoke, and the in-memory
 * `hash -> FrontendKeyRef` map the access plane matches a request against.
 *
 * The map is read without a database round trip. It is loaded by [ensureLoaded] (every suspend
 * method calls it; the access handler must call it once before it calls [match]) and refreshed on
 * create and revoke, so a revoked key is invalid from the next request on.
 *
 * The **internal key** is random per boot, has id 0, is never stored, and is the one
 * `UIManager.startUI` hands to the UIs Pano spawns as `PANO_FRONTEND_KEY`.
 */
@Lazy
@Component
class FrontendKeyService(
    private val frontendKeyDao: FrontendKeyDao
) {
    private val random = SecureRandom()

    private val keys = ConcurrentHashMap<String, FrontendKeyRef>()

    private val loadMutex = Mutex()

    @Volatile
    private var loaded = false

    private val lastTouched = ConcurrentHashMap<Long, Long>()

    /** The key of this boot's spawned UIs. Differs on every start of Pano. */
    val internalKey: String = newKey()

    private val internalHash = hash(internalKey)

    private val internalRef = FrontendKeyRef(FrontendKeyRef.INTERNAL_ID, "internal")

    /** Loads the stored keys once; later calls return at once. */
    suspend fun ensureLoaded(sqlClient: SqlClient) {
        if (loaded) return

        loadMutex.withLock {
            if (loaded) return

            reload(sqlClient)
        }
    }

    /** Replaces the map with what the table holds now. */
    suspend fun reload(sqlClient: SqlClient) {
        val fresh = frontendKeyDao.getAll(sqlClient).associate { it.keyHash to FrontendKeyRef(it.id, it.name) }

        keys.keys.retainAll(fresh.keys)
        keys.putAll(fresh)

        loaded = true
    }

    /** What [request] says about the front-end key. */
    fun match(request: HttpServerRequest): KeyMatch = matchHeader(request.getHeader(HEADER))

    /** [match] on the header value itself. */
    fun matchHeader(value: String?): KeyMatch {
        val key = value?.trim()

        if (key.isNullOrEmpty()) return KeyMatch.None

        val hash = hash(key)

        if (MessageDigest.isEqual(hash.toByteArray(), internalHash.toByteArray())) {
            return KeyMatch.Valid(internalRef)
        }

        return keys[hash]?.let { KeyMatch.Valid(it) } ?: KeyMatch.Invalid
    }

    suspend fun list(sqlClient: SqlClient): List<FrontendKey> = frontendKeyDao.getAll(sqlClient)

    /** Creates a key. Throws [FrontendKeyLimitReached] at [MAX_KEYS]. */
    suspend fun create(name: String, createdBy: Long, sqlClient: SqlClient): CreatedFrontendKey {
        ensureLoaded(sqlClient)

        if (frontendKeyDao.count(sqlClient) >= MAX_KEYS) {
            throw FrontendKeyLimitReached()
        }

        val key = newKey()
        val keyHash = hash(key)

        val id = frontendKeyDao.add(
            FrontendKey(name = name, keyHash = keyHash, keyHint = key.takeLast(HINT_LENGTH), createdBy = createdBy),
            sqlClient
        )

        keys[keyHash] = FrontendKeyRef(id, name)

        return CreatedFrontendKey(id, name, key)
    }

    /** Deletes the key; it stops matching at once. Returns the deleted row, or `null` when there is none. */
    suspend fun revoke(id: Long, sqlClient: SqlClient): FrontendKey? {
        ensureLoaded(sqlClient)

        val row = frontendKeyDao.getById(id, sqlClient) ?: return null

        frontendKeyDao.deleteById(id, sqlClient)

        keys.remove(row.keyHash)
        lastTouched.remove(id)

        return row
    }

    /** Records that [ref] was used, at most once a minute per key. The internal key is not recorded. */
    suspend fun markUsed(ref: FrontendKeyRef, sqlClient: SqlClient, now: Long = System.currentTimeMillis()) {
        if (ref.isInternal) return

        val previous = lastTouched.put(ref.id, now)

        if (previous != null && now - previous < TOUCH_INTERVAL_MS) {
            lastTouched[ref.id] = previous

            return
        }

        frontendKeyDao.updateLastUsedAt(ref.id, now, sqlClient)
    }

    private fun newKey(): String {
        val bytes = ByteArray(KEY_BYTES)

        random.nextBytes(bytes)

        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val HEADER = "X-Pano-Frontend-Key"
        const val KEY_PREFIX = "pfk_"
        const val MAX_KEYS = 20
        const val MAX_NAME_LENGTH = 64

        private const val KEY_BYTES = 32
        private const val HINT_LENGTH = 4
        private const val TOUCH_INTERVAL_MS = 60_000L

        /** SHA-256 of the key as lower-case hex: what the table stores. */
        fun hash(key: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(key.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
