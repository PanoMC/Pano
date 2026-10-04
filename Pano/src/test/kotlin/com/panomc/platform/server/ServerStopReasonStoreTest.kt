package com.panomc.platform.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** `lastStopReason` on the server JSON (SM-63): what is kept, what clears it, what is refused. */
class ServerStopReasonStoreTest {
    @Test
    fun `a start refused for want of Java is kept with its code and major`() {
        val store = ServerStopReasonStore()

        store.onState(1, ServerProcessState.STOPPED, "No Java 21 runtime on this host", "JAVA_MISSING", 21, now = 5)

        val kept = store.get(1)

        assertNotNull(kept)
        assertEquals("JAVA_MISSING", kept!!.reasonCode)
        assertEquals(21, kept.javaMajor)
        assertEquals(5L, kept.toJsonObject().getLong("at"))
    }

    @Test
    fun `starting again or a plain stop clears it`() {
        val store = ServerStopReasonStore()

        store.onState(1, ServerProcessState.STOPPED, "x", "JAVA_MISSING", 21)
        store.onState(1, ServerProcessState.STARTING, null, null, null)

        assertNull(store.get(1))

        store.onState(1, ServerProcessState.CRASHED, "OutOfMemoryError", null, null)
        assertEquals("OutOfMemoryError", store.get(1)?.reason)

        store.onState(1, ServerProcessState.STOPPED, null, null, null)
        assertNull(store.get(1))
    }

    @Test
    fun `untrusted codes and majors are dropped`() {
        val resolved = ServerStopReasonStore.resolve(ServerProcessState.STOPPED, "r", "<script>", 500, 0)

        assertNotNull(resolved)
        assertNull(resolved!!.reasonCode)
        assertNull(resolved.javaMajor)

        // A code alone is enough to keep a stop, a reason-less code-less one is not.
        assertNotNull(ServerStopReasonStore.resolve(ServerProcessState.STOPPED, null, "JAVA_MISSING", null, 0))
        assertNull(ServerStopReasonStore.resolve(ServerProcessState.STOPPED, null, "bad code", 21, 0))
        assertNull(ServerStopReasonStore.resolve(ServerProcessState.RUNNING, "r", "JAVA_MISSING", 21, 0))
    }
}
