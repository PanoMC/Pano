package com.panomc.platform.node

import com.github.jknack.handlebars.Handlebars
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NodeInstallScriptProviderTest {
    @Test
    fun `a quote in a pairing code cannot end the shell argument`() {
        assertEquals("123456", NodeInstallScriptProvider.shellQuote("123456"))
        assertEquals("a'\\''; rm -rf /", NodeInstallScriptProvider.shellQuote("a'; rm -rf /"))
    }

    @Test
    fun `a quote in a pairing code cannot end the PowerShell argument`() {
        assertEquals("123456", NodeInstallScriptProvider.powerShellQuote("123456"))
        assertEquals("a''; rm x", NodeInstallScriptProvider.powerShellQuote("a'; rm x"))
    }

    @Test
    fun `a development build is not a release tag`() {
        // The guard exists because "local-build" is a plausible-looking version that no release
        // is ever tagged with, and an installer pointed at v/local-build downloads nothing.
        assertEquals("local-build", NodeInstallScriptProvider.DEV_VERSION)
    }

    @Test
    fun `both templates render with nothing left unfilled`() {
        listOf(NodeInstallScriptProvider.SHELL_TEMPLATE, NodeInstallScriptProvider.POWERSHELL_TEMPLATE)
            .forEach { path ->
                val source = javaClass.classLoader.getResourceAsStream(path)
                    ?.bufferedReader()
                    ?.use { it.readText() }

                assertNotNull(source, "$path must ship with the jar")

                val rendered = Handlebars().compileInline(source!!).apply(
                    mapOf(
                        "version" to "1.2.3",
                        "downloadUrl" to "https://github.com/PanoMC/pano/releases/download/v1.2.3/pano-node.jar",
                        "checksumUrl" to "https://github.com/PanoMC/pano/releases/download/v1.2.3/pano-node.jar.sha256",
                        "jarName" to "pano-node.jar"
                    )
                )

                assertFalse(rendered.contains("{{"), "$path left a placeholder unrendered")
                assertTrue(rendered.contains("1.2.3"), "$path never used the version")
                // The URL survives the template engine unescaped, which is why they are triple
                // stashed: an escaped one would download nothing.
                assertTrue(
                    rendered.contains("https://github.com/PanoMC/pano/releases/download/v1.2.3/pano-node.jar"),
                    "$path mangled the download URL"
                )
            }
    }

    @Test
    fun `the jar comes from Pano when Pano has one, and from a release when it does not`() {
        // Serving it locally is what makes a node installable without any release existing, so
        // the local jar has to win over both the pinned tag and the latest-release fallback.
        assertEquals(
            "https://pano.example.com/api/node/pano-node.jar",
            NodeInstallScriptProvider.downloadUrl("https://pano.example.com", servedByPano = true, version = "1.2.3")
        )
        assertEquals(
            "https://github.com/PanoMC/pano/releases/download/v1.2.3/pano-node.jar",
            NodeInstallScriptProvider.downloadUrl("https://pano.example.com", servedByPano = false, version = "1.2.3")
        )
        assertEquals(
            "https://github.com/PanoMC/pano/releases/latest/download/pano-node.jar",
            NodeInstallScriptProvider.downloadUrl("https://pano.example.com", servedByPano = false, version = null)
        )
    }

    @Test
    fun `the shell installer knows the package manager of every distro it claims to support`() {
        val script = javaClass.classLoader.getResourceAsStream(NodeInstallScriptProvider.SHELL_TEMPLATE)!!
            .bufferedReader()
            .use { it.readText() }

        listOf("apt-get", "dnf", "yum", "zypper", "pacman", "apk").forEach { manager ->
            assertTrue(script.contains("command -v $manager"), "no branch for $manager")
        }

        // Arch has no headless JRE package to install and no default JVM until something sets
        // one, which is the pair of details that made the first Arch bootstrap fail.
        assertTrue(script.contains("jdk21-openjdk"), "pacman must install a JDK Arch actually ships")
        assertTrue(script.contains("archlinux-java"), "a freshly installed JVM must become the default")
    }

    @Test
    fun `the shell installer is a POSIX script that refuses a non-root install`() {
        val script = javaClass.classLoader.getResourceAsStream(NodeInstallScriptProvider.SHELL_TEMPLATE)!!
            .bufferedReader()
            .use { it.readText() }

        assertTrue(script.startsWith("#!/bin/sh"), "must be POSIX sh, not bash")
        assertTrue(script.contains("--user-install"), "must offer the unprivileged install")
        assertTrue(
            script.contains("SuccessExitStatus=75 78 143\n"),
            "systemd must treat a self update, a retirement and a stop as clean exits"
        )
    }
}
