package com.panomc.platform.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

class NetworkFailureUtilTest {
    @Test
    fun `an unresolvable host is a connectivity failure`() {
        val error = UnknownHostException("Failed to resolve 'api.github.com' [A(1)]")

        assertTrue(NetworkFailureUtil.isConnectivityFailure(error))
        assertEquals(
            "UnknownHostException: Failed to resolve 'api.github.com' [A(1)]",
            NetworkFailureUtil.describe(error)
        )
    }

    @Test
    fun `a connectivity failure is still recognised through a wrapper`() {
        val error = RuntimeException("Connection failed", ConnectException("Connection refused: /1.2.3.4:443"))

        assertTrue(NetworkFailureUtil.isConnectivityFailure(error))
        // Described from the root cause: the wrapper's own message says nothing useful.
        assertEquals("ConnectException: Connection refused: /1.2.3.4:443", NetworkFailureUtil.describe(error))
    }

    @Test
    fun `a timed out request is a connectivity failure`() {
        assertTrue(NetworkFailureUtil.isConnectivityFailure(TimeoutException("The timeout period of 5000ms has been exceeded")))
    }

    @Test
    fun `a bug is not a connectivity failure`() {
        // Must keep its stack trace at the call site - the frames are the only pointer to the bug.
        assertFalse(NetworkFailureUtil.isConnectivityFailure(NullPointerException()))
        assertFalse(NetworkFailureUtil.isConnectivityFailure(IllegalStateException("bad state")))
    }

    @Test
    fun `an error without a message is described by its type`() {
        assertEquals("UnknownHostException", NetworkFailureUtil.describe(UnknownHostException()))
    }

    @Test
    fun `a cyclic cause chain terminates`() {
        val first = RuntimeException("first")
        val second = RuntimeException("second", first)
        first.initCause(second)

        assertEquals("RuntimeException: second", NetworkFailureUtil.describe(first))
        assertFalse(NetworkFailureUtil.isConnectivityFailure(first))
    }
}
