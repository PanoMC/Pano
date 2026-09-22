package com.panomc.platform.server.alert

import com.panomc.platform.db.model.ServerAlert
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlertCooldownTrackerTest {
    private val cooldown = ServerAlertKind.TPS_LOW.cooldownMs

    @Test
    fun `says something the first time and stays quiet inside the cooldown`() {
        val tracker = AlertCooldownTracker()
        val now = 1_000_000L

        assertTrue(tracker.tryRaise(ServerAlertKind.TPS_LOW, "server:1", now))
        assertFalse(tracker.tryRaise(ServerAlertKind.TPS_LOW, "server:1", now))
        assertFalse(tracker.tryRaise(ServerAlertKind.TPS_LOW, "server:1", now + cooldown - 1))
    }

    @Test
    fun `says it again once the cooldown has passed`() {
        val tracker = AlertCooldownTracker()
        val now = 1_000_000L

        assertTrue(tracker.tryRaise(ServerAlertKind.TPS_LOW, "server:1", now))
        assertTrue(tracker.tryRaise(ServerAlertKind.TPS_LOW, "server:1", now + cooldown))
    }

    @Test
    fun `two subjects are two problems`() {
        val tracker = AlertCooldownTracker()
        val now = 1_000_000L

        assertTrue(tracker.tryRaise(ServerAlertKind.DISK_LOW, "node:1", now))
        assertTrue(tracker.tryRaise(ServerAlertKind.DISK_LOW, "node:2", now))
        assertFalse(tracker.tryRaise(ServerAlertKind.DISK_LOW, "node:1", now))
    }

    @Test
    fun `two kinds about one subject are two problems`() {
        val tracker = AlertCooldownTracker()
        val now = 1_000_000L

        assertTrue(tracker.tryRaise(ServerAlertKind.SERVER_CRASHED, "server:1", now))
        assertTrue(tracker.tryRaise(ServerAlertKind.BACKUP_FAILED, "server:1", now))
    }

    @Test
    fun `a condition that cleared is reported again at once`() {
        val tracker = AlertCooldownTracker()
        val now = 1_000_000L

        assertTrue(tracker.tryRaise(ServerAlertKind.NODE_OFFLINE, "node:1", now))
        assertFalse(tracker.tryRaise(ServerAlertKind.NODE_OFFLINE, "node:1", now + 1000))

        tracker.clear(ServerAlertKind.NODE_OFFLINE, "node:1")

        assertTrue(tracker.tryRaise(ServerAlertKind.NODE_OFFLINE, "node:1", now + 1000))
    }

    @Test
    fun `forgetting a subject drops every kind about it`() {
        val tracker = AlertCooldownTracker()
        val now = 1_000_000L

        tracker.tryRaise(ServerAlertKind.SERVER_CRASHED, "server:7", now)
        tracker.tryRaise(ServerAlertKind.TPS_LOW, "server:7", now)

        tracker.forget("server:7")

        assertTrue(tracker.tryRaise(ServerAlertKind.SERVER_CRASHED, "server:7", now))
        assertTrue(tracker.tryRaise(ServerAlertKind.TPS_LOW, "server:7", now))
    }

    // ------------------------------------------------------------ stored history (SM-69, §2.4.34)

    @Test
    fun `a restart does not reset the cooldown when the alert is on record`() = runBlocking<Unit> {
        val daily = ServerAlertKind.PLUGIN_UPDATES.cooldownMs
        val raisedAt = 1_000_000L

        // Before the restart: raised once, and the row AlertManager writes for it.
        val before = AlertCooldownTracker()
        assertTrue(before.tryRaise(ServerAlertKind.PLUGIN_UPDATES, "server:3", raisedAt) { null })
        val stored = ServerAlert(kind = ServerAlertKind.PLUGIN_UPDATES, serverId = 3, message = "", createdAt = raisedAt)

        // After it: a fresh tracker, and the sweep fifteen minutes after boot.
        val after = AlertCooldownTracker()
        val fifteenMinutesLater = raisedAt + 15 * 60 * 1000L

        assertFalse(
            after.tryRaise(ServerAlertKind.PLUGIN_UPDATES, "server:3", fifteenMinutesLater) {
                AlertSubject.lastRaiseOf(stored)
            }
        )
        // Tomorrow's sweep is news again.
        assertTrue(
            after.tryRaise(ServerAlertKind.PLUGIN_UPDATES, "server:3", raisedAt + daily) {
                AlertSubject.lastRaiseOf(stored)
            }
        )
    }

    @Test
    fun `nothing on record raises at once`() = runBlocking<Unit> {
        val tracker = AlertCooldownTracker()

        assertTrue(tracker.tryRaise(ServerAlertKind.PLUGIN_UPDATES, "server:3", 1_000_000L) { null })
    }

    @Test
    fun `a stored raise older than the cooldown does not hold anything back`() = runBlocking<Unit> {
        val tracker = AlertCooldownTracker()
        val now = 10 * ServerAlertKind.PLUGIN_UPDATES.cooldownMs

        assertTrue(
            tracker.tryRaise(ServerAlertKind.PLUGIN_UPDATES, "server:3", now) {
                now - ServerAlertKind.PLUGIN_UPDATES.cooldownMs
            }
        )
    }

    @Test
    fun `the table is asked once per key, then memory is the fast path`() = runBlocking<Unit> {
        val tracker = AlertCooldownTracker()
        var lookups = 0
        val lookup: suspend () -> Long? = { lookups++; null }

        tracker.tryRaise(ServerAlertKind.TPS_LOW, "server:1", 1_000_000L, lookup)
        tracker.tryRaise(ServerAlertKind.TPS_LOW, "server:1", 2_000_000L, lookup)
        tracker.tryRaise(ServerAlertKind.TPS_LOW, "server:1", 3_000_000L + cooldown, lookup)

        assertEquals(1, lookups)

        // Another subject is another history.
        tracker.tryRaise(ServerAlertKind.TPS_LOW, "server:2", 1_000_000L, lookup)

        assertEquals(2, lookups)
    }

    @Test
    fun `a condition cleared in this process is not held back by the row before it`() = runBlocking<Unit> {
        val tracker = AlertCooldownTracker()
        val now = 1_000_000L

        // The node came back after the restart, then dropped again: the stored outage predates the
        // recovery this process saw, so the new outage is reported at once.
        tracker.clear(ServerAlertKind.NODE_OFFLINE, "node:1")

        assertTrue(tracker.tryRaise(ServerAlertKind.NODE_OFFLINE, "node:1", now) { now - 1000 })
    }

    @Test
    fun `a resolved row does not count as a raise`() {
        val resolved = ServerAlert(
            kind = ServerAlertKind.NODE_OFFLINE,
            nodeId = 1,
            message = "",
            createdAt = 1_000L,
            resolvedAt = 2_000L
        )
        val open = resolved.copy(resolvedAt = null)

        assertEquals(null, AlertSubject.lastRaiseOf(resolved))
        assertEquals(null, AlertSubject.lastRaiseOf(null))
        assertEquals(1_000L, AlertSubject.lastRaiseOf(open))
    }

    @Test
    fun `forgetting a subject forgets its history too`() = runBlocking<Unit> {
        val tracker = AlertCooldownTracker()
        var lookups = 0
        val lookup: suspend () -> Long? = { lookups++; null }

        tracker.tryRaise(ServerAlertKind.SERVER_CRASHED, "server:7", 1_000_000L, lookup)
        tracker.forget("server:7")
        tracker.tryRaise(ServerAlertKind.SERVER_CRASHED, "server:7", 1_000_000L, lookup)

        assertEquals(2, lookups)
    }
}
