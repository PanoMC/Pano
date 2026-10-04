package com.panomc.platform.hosted

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HostSsoUserMapperTest {
    private class MemoryStore : HostSsoUserStore {
        data class U(val id: Long, val username: String, val email: String, var admin: Boolean, var banned: Boolean = false, val password: String = "")

        val users = mutableListOf<U>()
        val mappings = mutableMapOf<String, Long>()

        fun add(username: String, email: String, admin: Boolean) = U(users.size + 1L, username, email, admin).also { users += it }
        fun user(id: Long) = users.single { it.id == id }

        override suspend fun mappedUserId(key: String) = mappings[key]
        override suspend fun setMapping(key: String, userId: Long) { mappings[key] = userId }
        override suspend fun userExists(userId: Long) = users.any { it.id == userId }
        override suspend fun userIdByEmail(email: String) = users.firstOrNull { it.email.equals(email, true) }?.id
        override suspend fun userIdByUsername(username: String) = users.firstOrNull { it.username.equals(username, true) }?.id
        override suspend fun isAdmin(userId: Long) = user(userId).admin
        override suspend fun isBanned(userId: Long) = user(userId).banned
        override suspend fun usernameTaken(username: String) = users.any { it.username.equals(username, true) }
        override suspend fun emailTaken(email: String) = users.any { it.email.equals(email, true) }
        override suspend fun createUser(username: String, email: String, password: String) =
            U(users.size + 1L, username, email, false, password = password).also { users += it }.id
        override suspend fun grantAdmin(userId: Long) { user(userId).admin = true }
    }

    private fun identity(
        accountId: String,
        email: String,
        role: String = "owner",
        username: String = email.substringBefore('@'),
        adminUsername: String? = null
    ) = PanoHostClient.SsoIdentity(accountId, email, username, role, "w1", "shop.panomc.site", adminUsername)

    private val store = MemoryStore()
    private val logs = mutableListOf<String>()
    private val mapper = HostSsoUserMapper(store, log = { logs += it })

    @Test
    fun `maps to the local admin with the same email and remembers it`() = runBlocking {
        val admin = store.add("steve", "owner@example.com", admin = true)

        val first = mapper.resolve(identity("acc-1", "OWNER@example.com"))
        assertEquals(HostSsoUserMapper.Mapping(admin.id, false), first)
        assertEquals(admin.id, store.mappings["pano_host_sso_account:acc-1"])
        assertEquals(1, store.users.size)
    }

    @Test
    fun `creates a host_ admin with a random password when no admin has the email`() = runBlocking {
        val player = store.add("owner", "owner@example.com", admin = false)

        val mapping = mapper.resolve(identity("acc-1", "owner@example.com"))
        assertTrue(mapping.created)
        val created = store.user(mapping.userId)
        assertNotEquals(player.id, created.id)
        assertFalse(player.admin, "a same-email player is never promoted")
        assertEquals("host_owner", created.username)
        assertTrue(created.admin)
        assertEquals("host_owner@shop.panomc.site", created.email, "email taken by the player → placeholder")
        assertTrue(created.password.length >= 40)
        assertTrue(created.username.matches(Regex("^[a-zA-Z0-9_]+$")))

        // Second login reuses the same local user.
        assertEquals(HostSsoUserMapper.Mapping(created.id, false), mapper.resolve(identity("acc-1", "owner@example.com")))
    }

    @Test
    fun `new account keeps its real email, sanitised and truncated username, collisions suffixed`() = runBlocking {
        store.add("host_averylongna", "x@example.com", admin = false)

        val mapping = mapper.resolve(identity("acc-2", "a.very-long.name.indeed@example.com"))
        val created = store.user(mapping.userId)
        assertEquals("a.very-long.name.indeed@example.com", created.email)
        assertEquals("host_averylongn2", created.username)
        assertEquals(16, created.username.length)
    }

    @Test
    fun `two accounts with the same local part never share a user`() = runBlocking {
        val a = mapper.resolve(identity("acc-a", "admin@a.com"))
        val b = mapper.resolve(identity("acc-b", "admin@b.com"))

        assertNotEquals(a.userId, b.userId)
        assertEquals("host_admin", store.user(a.userId).username)
        assertEquals("host_admin2", store.user(b.userId).username)
    }

    @Test
    fun `support maps to one dedicated support admin, never the owner`() = runBlocking {
        val owner = store.add("steve", "owner@example.com", admin = true)

        val first = mapper.resolve(identity("staff-1", "owner@example.com", role = "support"))
        val second = mapper.resolve(identity("staff-2", "staff2@panomc.com", role = "support"))

        assertNotEquals(owner.id, first.userId)
        assertEquals(first.userId, second.userId)
        val support = store.user(first.userId)
        assertEquals("pano_support", support.username)
        assertEquals("pano_support@shop.panomc.site", support.email)
        assertTrue(support.admin)
        assertEquals(support.id, store.mappings[HostSsoUserMapper.SUPPORT_KEY])
    }

    @Test
    fun `re-grants admin to a mapped user and refuses a banned one`() = runBlocking {
        val mapping = mapper.resolve(identity("acc-1", "owner@example.com"))
        val user = store.user(mapping.userId)

        user.admin = false
        mapper.resolve(identity("acc-1", "owner@example.com"))
        assertTrue(user.admin)

        user.banned = true
        assertThrows(HostSsoUserMapper.Denied::class.java) { runBlocking { mapper.resolve(identity("acc-1", "owner@example.com")) } }
        Unit
    }

    @Test
    fun `a deleted mapped user is recreated`() = runBlocking {
        val first = mapper.resolve(identity("acc-1", "owner@example.com"))
        store.users.removeIf { it.id == first.userId }

        val second = mapper.resolve(identity("acc-1", "owner@example.com"))
        assertTrue(second.created)
        assertEquals(second.userId, store.mappings["pano_host_sso_account:acc-1"])
    }

    @Test
    fun `signs the owner in as the chosen admin without touching the mapping`() = runBlocking {
        val default = mapper.resolve(identity("acc-1", "owner@example.com"))
        val chosen = store.add("Builder", "builder@example.com", admin = true)

        val mapping = mapper.resolve(identity("acc-1", "owner@example.com", adminUsername = "builder"))
        assertEquals(HostSsoUserMapper.Mapping(chosen.id, created = false, chosen = true), mapping)
        assertEquals(default.userId, store.mappings["pano_host_sso_account:acc-1"], "account mapping untouched")

        // Back to automatic → the remembered user again.
        assertEquals(HostSsoUserMapper.Mapping(default.userId, false), mapper.resolve(identity("acc-1", "owner@example.com")))
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `a chosen non-admin is never promoted and falls back to the mapping`() = runBlocking {
        val default = mapper.resolve(identity("acc-1", "owner@example.com"))
        val player = store.add("player", "player@example.com", admin = false)

        val mapping = mapper.resolve(identity("acc-1", "owner@example.com", adminUsername = "player"))
        assertEquals(HostSsoUserMapper.Mapping(default.userId, false), mapping)
        assertFalse(player.admin)
        assertTrue(logs.single().contains("is not an admin"))

        logs.clear()
        assertEquals(default.userId, mapper.resolve(identity("acc-1", "owner@example.com", adminUsername = "ghost")).userId)
        assertTrue(logs.single().contains("does not exist"))
    }

    @Test
    fun `a banned chosen admin falls back, or is denied when it is the default mapping`() = runBlocking {
        val default = mapper.resolve(identity("acc-1", "owner@example.com"))
        val banned = store.add("banned_admin", "b@example.com", admin = true).also { it.banned = true }

        val mapping = mapper.resolve(identity("acc-1", "owner@example.com", adminUsername = banned.username))
        assertEquals(default.userId, mapping.userId)
        assertFalse(mapping.chosen)
        assertTrue(logs.single().contains("is banned"))

        val self = store.user(default.userId)
        self.banned = true
        assertThrows(HostSsoUserMapper.Denied::class.java) {
            runBlocking { mapper.resolve(identity("acc-1", "owner@example.com", adminUsername = self.username)) }
        }
        Unit
    }

    @Test
    fun `support tickets ignore a chosen admin`() = runBlocking {
        val owner = store.add("steve", "owner@example.com", admin = true)

        val mapping = mapper.resolve(identity("staff-1", "staff@panomc.com", role = "support", adminUsername = owner.username))
        assertNotEquals(owner.id, mapping.userId)
        assertFalse(mapping.chosen)
        assertEquals("pano_support", store.user(mapping.userId).username)
    }
}
