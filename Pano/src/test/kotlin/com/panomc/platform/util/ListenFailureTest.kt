package com.panomc.platform.util

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.BindException
import java.net.SocketException

class ListenFailureTest {
    @Test
    fun `a privileged port asks for sudo on Linux and macOS`() {
        listOf(OperatingSystem.LINUX, OperatingSystem.DARWIN).forEach { os ->
            val message = ListenFailure.message("http", "0.0.0.0", 80, SocketException("Permission denied"), os, "http-port")

            assertTrue(message.startsWith("Failed to listen on http://0.0.0.0:80, reason: Permission denied."))
            assertTrue(message.contains("run Pano with sudo"))
            assertTrue(message.contains("\"http-port\""))
        }
    }

    @Test
    fun `a privileged port asks for an administrator on Windows`() {
        val message = ListenFailure.message(
            "https", "0.0.0.0", 443, SocketException("Permission denied: bind"), OperatingSystem.WINDOWS, "https-port"
        )

        assertTrue(message.contains("run Pano as administrator"))
        assertFalse(message.contains("sudo"))
        assertTrue(message.contains("\"https-port\""))
    }

    @Test
    fun `a taken port points at the other program instead of privileges`() {
        val message = ListenFailure.message(
            "http", "0.0.0.0", 8088, BindException("Address already in use"), OperatingSystem.LINUX, "http-port"
        )

        assertTrue(message.contains("Port 8088 is used by another program"))
        assertFalse(message.contains("sudo"))
        assertTrue(message.contains("Pano keeps running"))
    }

    @Test
    fun `an unknown reason still says that Pano keeps running`() {
        val message = ListenFailure.message(
            "http", "bad-host", 8088, RuntimeException("Cannot assign requested address"), OperatingSystem.LINUX, "http-port"
        )

        assertTrue(message.contains("reason: Cannot assign requested address."))
        assertTrue(message.contains("Pano keeps running"))
    }
}
