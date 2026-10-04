package com.panomc.platform.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MinecraftJavaVersionsTest {
    @Test
    fun `follows mojang's ladder`() {
        assertEquals(21, MinecraftJavaVersions.minimumFor("1.21.8"))
        assertEquals(21, MinecraftJavaVersions.minimumFor("1.20.5"))
        assertEquals(17, MinecraftJavaVersions.minimumFor("1.20.4"))
        assertEquals(17, MinecraftJavaVersions.minimumFor("1.17"))
        assertEquals(16, MinecraftJavaVersions.minimumFor("1.16.5"))
        assertEquals(8, MinecraftJavaVersions.minimumFor("1.16.4"))
        assertEquals(8, MinecraftJavaVersions.minimumFor("1.8.8"))
    }

    @Test
    fun `treats a version that is not a 1 dot x as modern`() {
        // Minecraft's 2026 versions, and a proxy's own version number.
        assertEquals(25, MinecraftJavaVersions.minimumFor("26.3"))
        assertEquals(21, MinecraftJavaVersions.minimumFor("3.5.1"))
        assertEquals(21, MinecraftJavaVersions.minimumFor(null))
        assertEquals(21, MinecraftJavaVersions.minimumFor("24w45a"))
    }

    @Test
    fun `reads a pre-release as the version it precedes`() {
        assertEquals(21, MinecraftJavaVersions.minimumFor("1.21.11-rc3"))
        assertEquals(17, MinecraftJavaVersions.minimumFor("1.20.1-rc1"))
    }

    @Test
    fun `caps everything older than 1 dot 17, which does not start on java 17`() {
        assertNull(MinecraftJavaVersions.maximumFor("1.21.8"))
        assertNull(MinecraftJavaVersions.maximumFor("1.17"))
        assertNull(MinecraftJavaVersions.maximumFor("26.3"))
        assertEquals(16, MinecraftJavaVersions.maximumFor("1.16.5"))
        assertEquals(16, MinecraftJavaVersions.maximumFor("1.12.2"))
    }

    @Test
    fun `picks the minimum a version needs rather than the newest installed`() {
        // Paper 1.21.8 on a host that also has 24 and 26: newest-wins crashed it on shutdown.
        assertEquals(21, MinecraftJavaVersions.pick(listOf(17, 21, 24), "1.21.8"))
        assertEquals(21, MinecraftJavaVersions.pick(listOf(21, 24, 26), "1.21.8"))
        assertEquals(17, MinecraftJavaVersions.pick(listOf(8, 17, 21), "1.20.4"))
        assertEquals(8, MinecraftJavaVersions.pick(listOf(8, 11, 21), "1.12.2"))
        assertEquals(16, MinecraftJavaVersions.pick(listOf(8, 16, 21), "1.16.5"))
    }

    @Test
    fun `climbs to the lowest supported runtime when the minimum is not installed`() {
        assertEquals(24, MinecraftJavaVersions.pick(listOf(24, 26), "1.21.8"))
        assertEquals(11, MinecraftJavaVersions.pick(listOf(11, 16, 21), "1.8.8"))
    }

    @Test
    fun `picks an old runtime for an old server even on a modern host`() {
        assertEquals(8, MinecraftJavaVersions.pick(listOf(8, 21), "1.16.5"))
        assertEquals(16, MinecraftJavaVersions.pick(listOf(16, 21, 26), "1.12.2"))
    }

    @Test
    fun `settles for the lowest runtime above the minimum when nothing else fits`() {
        assertEquals(21, MinecraftJavaVersions.pick(listOf(21, 24), "1.12.2"))
        assertNull(MinecraftJavaVersions.pick(listOf(8, 11), "1.21.8"))
        assertNull(MinecraftJavaVersions.pick(emptyList(), "1.21.8"))
    }

    @Test
    fun `says why it chose a runtime`() {
        assertTrue(
            MinecraftJavaVersions.choose(listOf(17, 21, 26), "1.21.8")!!.reason.contains("minimum"),
            "the minimum being installed is the reason worth logging"
        )

        assertTrue(
            MinecraftJavaVersions.choose(listOf(24, 26), "1.21.8")!!.reason.contains("lowest runtime"),
            "climbing past an absent minimum should say so"
        )

        assertTrue(
            MinecraftJavaVersions.choose(listOf(8, 21), "1.16.5")!!.reason.contains("old enough"),
            "an old server held back from a modern runtime should say so"
        )

        assertNull(MinecraftJavaVersions.choose(emptyList(), "1.21.8"))
    }
}
