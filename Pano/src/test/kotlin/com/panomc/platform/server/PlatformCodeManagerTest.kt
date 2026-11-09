package com.panomc.platform.server

import io.vertx.core.Vertx
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlatformCodeManagerTest {
    private val vertx = Vertx.vertx()

    @AfterEach
    fun close() {
        vertx.close()
    }

    @Test
    fun `the current code is accepted and a wrong one is not`() {
        val manager = PlatformCodeManager(vertx)
        val code = manager.getPlatformKey().toString()

        assertTrue(manager.verify(code, 1_000))
        assertFalse(manager.verify("000000", 1_000))
        assertFalse(manager.verify(null, 1_000))
    }

    @Test
    fun `too many wrong codes lock every attempt until the window ends`() {
        val manager = PlatformCodeManager(vertx)
        val code = manager.getPlatformKey().toString()
        val wrong = if (code == "100000") "100001" else "100000"

        repeat(PlatformCodeManager.MAX_FAILURES) { assertFalse(manager.verify(wrong, 1_000)) }

        assertFalse(manager.verify(code, 2_000))
        assertTrue(manager.verify(code, 1_000 + PlatformCodeManager.FAILURE_WINDOW_MS))
    }
}
