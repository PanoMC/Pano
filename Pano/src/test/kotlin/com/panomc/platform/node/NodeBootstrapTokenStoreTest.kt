package com.panomc.platform.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NodeBootstrapTokenStoreTest {
    @Test
    fun `accepts a freshly issued token`() {
        val store = NodeBootstrapTokenStore()
        val token = store.issue(now = 1000)

        assertNotNull(store.consume(token, now = 1000))
    }

    @Test
    fun `accepts a token only once`() {
        val store = NodeBootstrapTokenStore()
        val token = store.issue(now = 1000)

        assertNotNull(store.consume(token, now = 1000))
        assertNull(store.consume(token, now = 1000))
    }

    @Test
    fun `rejects a token past its ttl`() {
        val store = NodeBootstrapTokenStore()
        val token = store.issue(now = 1000)

        assertNull(store.consume(token, now = 1000 + NodeBootstrapTokenStore.TOKEN_TTL_MS + 1))
    }

    @Test
    fun `accepts a token on the last millisecond before expiry`() {
        val store = NodeBootstrapTokenStore()
        val token = store.issue(now = 1000)

        assertNotNull(store.consume(token, now = 1000 + NodeBootstrapTokenStore.TOKEN_TTL_MS - 1))
    }

    @Test
    fun `rejects an unknown or blank token`() {
        val store = NodeBootstrapTokenStore()
        store.issue(now = 1000)

        assertNull(store.consume("not-a-token", now = 1000))
        assertNull(store.consume(null, now = 1000))
        assertNull(store.consume("   ", now = 1000))
    }

    @Test
    fun `hands back what the token was minted for`() {
        val store = NodeBootstrapTokenStore()

        val local = store.issue(now = 1000)
        val ssh = store.issue(NodeBootstrapGrant.remote(NodeBootstrap.SSH), now = 1000)

        assertEquals(NodeBootstrapGrant.LOCAL_NODE, store.consume(local, now = 1000))
        assertEquals(NodeBootstrapGrant(NodeKind.REMOTE, NodeBootstrap.SSH), store.consume(ssh, now = 1000))
    }

    @Test
    fun `issues distinct tokens`() {
        val store = NodeBootstrapTokenStore()

        val tokens = (1..100).map { store.issue(now = 1000) }.toSet()

        assertEquals(100, tokens.size)
    }

    @Test
    fun `forgets expired tokens without anyone asking for them`() {
        val store = NodeBootstrapTokenStore()
        store.issue(now = 1000)
        store.issue(now = 1000)

        assertEquals(2, store.size(now = 1000))
        assertEquals(0, store.size(now = 1000 + NodeBootstrapTokenStore.TOKEN_TTL_MS + 1))
    }

    @Test
    fun `revokes a token that was never used`() {
        val store = NodeBootstrapTokenStore()
        val token = store.issue(now = 1000)

        store.revoke(token)

        assertNull(store.consume(token, now = 1000))
    }
}
