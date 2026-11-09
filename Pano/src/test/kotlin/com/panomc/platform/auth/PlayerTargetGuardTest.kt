package com.panomc.platform.auth

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayerTargetGuardTest {
    private fun covered(target: Set<String>, issuer: Set<String>, wildcard: (String) -> Boolean = { false }) =
        runBlocking { PlayerTargetGuard.isCovered(target, issuer) { wildcard(it) } }

    @Test
    fun `a moderator can manage a plain player`() {
        assertTrue(covered(setOf("group.vip", "essentials.fly", "prefix.10.VIP"), setOf("pano.panel.access.panel", "pano.panel.manage.players")))
    }

    @Test
    fun `a moderator can manage a colleague with the same rights`() {
        val rights = setOf("pano.panel.access.panel", "pano.panel.manage.players")

        assertTrue(covered(rights, rights))
    }

    @Test
    fun `a moderator cannot manage a colleague with more rights`() {
        assertFalse(
            covered(
                setOf("pano.panel.access.panel", "pano.panel.manage.addons"),
                setOf("pano.panel.access.panel", "pano.panel.manage.players")
            )
        )
    }

    @Test
    fun `a right granted through a wildcard counts`() {
        assertTrue(
            covered(setOf("pano.panel.manage.addons"), setOf("pano.panel.*")) { it.startsWith("pano.panel.") }
        )
    }

    @Test
    fun `an administrator target needs an administrator`() {
        assertFalse(covered(setOf("*"), setOf("pano.panel.manage.players")) { true })
        assertTrue(covered(setOf("*"), setOf("*")) { true })
    }
}
