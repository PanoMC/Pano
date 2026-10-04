package com.panomc.platform.server.alert

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The cooldown subjects `AlertManager` builds, read back as `server_alert` columns (SM-69, §2.4.34). */
class AlertSubjectTest {
    @Test
    fun `a server subject is the server's rows`() {
        assertEquals(AlertSubject(serverId = 12, nodeId = null), AlertSubject.parse("server:12"))
    }

    @Test
    fun `a node subject is the node's own rows`() {
        assertEquals(AlertSubject(serverId = null, nodeId = 4), AlertSubject.parse("node:4"))
    }

    @Test
    fun `a schedule subject has no stored shape`() {
        // The schedule's name is only in the message; by server alone one failing schedule would
        // silence another.
        assertNull(AlertSubject.parse("server:12:nightly-restart"))
    }

    @Test
    fun `anything else has no stored shape`() {
        assertNull(AlertSubject.parse("server:abc"))
        assertNull(AlertSubject.parse("player:1"))
        assertNull(AlertSubject.parse(""))
    }
}
