package com.panomc.platform.access

import com.panomc.platform.db.dao.FrontendKeyDao
import com.panomc.platform.db.model.FrontendKey
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * Front-end keys (open front-end plan, doc 05 §3.2): create -> match -> revoke, the internal key,
 * and that the table only ever holds the hash.
 */
class FrontendKeyServiceTest {
    /** The DAO in memory; it never touches the [SqlClient] it is handed. */
    private class MemoryDao : FrontendKeyDao() {
        val rows = linkedMapOf<Long, FrontendKey>()
        val lastUsedUpdates = mutableListOf<Pair<Long, Long>>()
        private var nextId = 1L

        override suspend fun init(sqlClient: SqlClient) {}

        override suspend fun add(frontendKey: FrontendKey, sqlClient: SqlClient): Long {
            val id = nextId++

            rows[id] = frontendKey.copy(id = id)

            return id
        }

        override suspend fun getAll(sqlClient: SqlClient) = rows.values.toList()

        override suspend fun getById(id: Long, sqlClient: SqlClient) = rows[id]

        override suspend fun count(sqlClient: SqlClient) = rows.size

        override suspend fun deleteById(id: Long, sqlClient: SqlClient) = rows.remove(id) != null

        override suspend fun updateLastUsedAt(id: Long, lastUsedAt: Long, sqlClient: SqlClient) {
            lastUsedUpdates += id to lastUsedAt
        }
    }

    private val sqlClient: SqlClient = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(SqlClient::class.java)
    ) { _, method, _ -> error("the service must not use the SqlClient itself: ${method.name}") } as SqlClient

    private val dao = MemoryDao()
    private val service = FrontendKeyService(dao)

    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    @Test
    fun `create then match is Valid, revoke then match is Invalid`() = run {
        val created = service.create("Edge", 7, sqlClient)

        val match = service.matchHeader(created.key)

        assertTrue(match is KeyMatch.Valid)
        assertEquals(FrontendKeyRef(created.id, "Edge"), (match as KeyMatch.Valid).ref)
        assertFalse(match.ref.isInternal)

        assertNotNull(service.revoke(created.id, sqlClient))

        assertEquals(KeyMatch.Invalid, service.matchHeader(created.key))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `a key has the documented shape and a hint of its last four characters`() = run {
        val created = service.create("Edge", 1, sqlClient)

        // pfk_ + 32 random bytes as unpadded base64url = 43 characters.
        assertTrue(Regex("^pfk_[A-Za-z0-9_-]{43}$").matches(created.key), created.key)
        assertEquals(created.key.takeLast(4), dao.rows.getValue(created.id).keyHint)
        assertNotEquals(service.create("Other", 1, sqlClient).key, created.key)
    }

    @Test
    fun `the table holds the SHA-256 of the key and never the key`() = run {
        val created = service.create("Edge", 3, sqlClient)
        val row = dao.rows.getValue(created.id)

        assertEquals(64, row.keyHash.length)
        assertEquals(FrontendKeyService.hash(created.key), row.keyHash)
        // Known vector: SHA-256("abc").
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            FrontendKeyService.hash("abc")
        )
        assertEquals(3L, row.createdBy)
        assertNull(row.lastUsedAt)
        assertFalse(row.toString().contains(created.key))
        assertFalse(dao.rows.values.any { created.key in it.toString() })
    }

    @Test
    fun `no header or an empty one is None, an unknown key is Invalid`() = run {
        assertEquals(KeyMatch.None, service.matchHeader(null))
        assertEquals(KeyMatch.None, service.matchHeader(""))
        assertEquals(KeyMatch.None, service.matchHeader("   "))
        assertEquals(KeyMatch.Invalid, service.matchHeader("pfk_nope"))
    }

    @Test
    fun `the internal key matches with id 0 and is never stored`() = run {
        val match = service.matchHeader(service.internalKey)

        assertTrue(match is KeyMatch.Valid)
        assertEquals(0L, (match as KeyMatch.Valid).ref.id)
        assertTrue(match.ref.isInternal)
        assertTrue(Regex("^pfk_[A-Za-z0-9_-]{43}$").matches(service.internalKey))
        assertTrue(dao.rows.isEmpty())

        // Another boot, another internal key; the old one is Invalid there.
        val nextBoot = FrontendKeyService(MemoryDao())

        assertNotEquals(service.internalKey, nextBoot.internalKey)
        assertEquals(KeyMatch.Invalid, nextBoot.matchHeader(service.internalKey))
    }

    @Test
    fun `stored keys are picked up after a restart`() = run {
        val created = service.create("Edge", 1, sqlClient)

        val restarted = FrontendKeyService(dao)

        restarted.ensureLoaded(sqlClient)

        assertEquals(KeyMatch.Valid(FrontendKeyRef(created.id, "Edge")), restarted.matchHeader(created.key))
    }

    @Test
    fun `at most 20 keys exist`() = run {
        repeat(FrontendKeyService.MAX_KEYS) { service.create("k$it", 1, sqlClient) }

        assertThrows(FrontendKeyLimitReached::class.java) { run { service.create("one too many", 1, sqlClient) } }
        assertEquals(FrontendKeyService.MAX_KEYS, dao.rows.size)

        // Revoking one makes room again.
        service.revoke(dao.rows.keys.first(), sqlClient)
        service.create("again", 1, sqlClient)
        assertEquals(FrontendKeyService.MAX_KEYS, dao.rows.size)
    }

    @Test
    fun `revoking a missing key returns null`() = run {
        assertNull(service.revoke(99, sqlClient))
    }

    @Test
    fun `last use is written at most once a minute and never for the internal key`() = run {
        val created = service.create("Edge", 1, sqlClient)
        val ref = FrontendKeyRef(created.id, "Edge")

        service.markUsed(ref, sqlClient, now = 1_000)
        service.markUsed(ref, sqlClient, now = 30_000)
        service.markUsed(ref, sqlClient, now = 61_500)
        service.markUsed(FrontendKeyRef(FrontendKeyRef.INTERNAL_ID, "internal"), sqlClient, now = 200_000)

        assertEquals(listOf(created.id to 1_000L, created.id to 61_500L), dao.lastUsedUpdates)
    }
}
