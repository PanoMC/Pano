package com.panomc.platform.db

import com.panomc.platform.db.implementation.ResourceHashDaoImpl
import com.panomc.platform.db.implementation.ServerPlayerDaoImpl
import com.panomc.platform.db.model.ResourceHash
import com.panomc.platform.db.model.ServerPlayer
import com.panomc.platform.util.ResourceHashStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID

/**
 * Names and hashes that go into an `IN (...)` list are bound, never spliced into the SQL: a stored player name
 * with a quote is looked up like any other and cannot change the query.
 */
@EnabledIfEnvironmentVariable(named = "PANO_IT_MARIADB", matches = ".+")
class InListBindingMariaDbIT {
    @Test
    fun `player names with quotes are bound`() = runBlocking {
        DaoMariaDb().use { db ->
            val dao = ServerPlayerDaoImpl()

            db.dropTable("server_player")
            dao.init(db.pool)

            val evil = "x') OR 1=1 OR username IN ('y"

            dao.add(ServerPlayer(uuid = UUID.randomUUID(), username = "it's", serverId = 1, loginTime = 0), db.pool)
            dao.add(ServerPlayer(uuid = UUID.randomUUID(), username = "plain", serverId = 1, loginTime = 0), db.pool)

            val found = dao.existsByUsernameList(listOf("it's", evil, "missing"), db.pool)

            assertEquals(mapOf("it's" to true), found)

            db.dropTable("server_player")
        }
    }

    @Test
    fun `resource hashes are bound`() = runBlocking {
        DaoMariaDb().use { db ->
            val dao = ResourceHashDaoImpl()

            db.dropTable("resource_hash")
            dao.init(db.pool)

            dao.add(ResourceHash(hash = "abc", status = ResourceHashStatus.entries.first()), db.pool)

            val found = dao.byListOfHash(listOf("abc", "') OR ('1'='1"), db.pool)

            assertEquals(setOf("abc"), found.keys)
            assertTrue(dao.byListOfHash(listOf("') OR ('1'='1"), db.pool).isEmpty())

            db.dropTable("resource_hash")
        }
    }
}
