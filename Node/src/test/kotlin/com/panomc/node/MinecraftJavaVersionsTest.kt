package com.panomc.node

import com.panomc.node.server.MinecraftJavaVersions
import com.panomc.node.server.ServerSpec
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
        assertEquals(25, MinecraftJavaVersions.minimumFor("26.3"))
        assertEquals(21, MinecraftJavaVersions.minimumFor(null))
    }

    @Test
    fun `knows which versions cannot use java 17`() {
        assertNull(MinecraftJavaVersions.maximumFor("1.21.8"))
        assertEquals(16, MinecraftJavaVersions.maximumFor("1.16.5"))
        assertEquals(16, MinecraftJavaVersions.maximumFor("1.8.8"))
    }

    @Test
    fun `picks the minimum the version needs rather than the newest installed`() {
        // The whole point: Paper 1.21.8 on Java 26 starts, then dies in native code on shutdown.
        assertEquals(21, MinecraftJavaVersions.pick(listOf(8, 17, 21, 24), "1.21.8"))
        assertEquals(21, MinecraftJavaVersions.pick(listOf(21, 26), "1.21.8"))
        assertEquals(17, MinecraftJavaVersions.pick(listOf(17, 21, 24), "1.20.4"))
        assertEquals(8, MinecraftJavaVersions.pick(listOf(8, 11, 21), "1.12.2"))
    }

    @Test
    fun `falls back in order when the minimum is missing`() {
        assertEquals(24, MinecraftJavaVersions.pick(listOf(24, 26), "1.21.8"))
        assertEquals(8, MinecraftJavaVersions.pick(listOf(8, 21), "1.16.5"))
        assertEquals(21, MinecraftJavaVersions.pick(listOf(21, 24), "1.12.2"))
        assertNull(MinecraftJavaVersions.pick(listOf(8, 11), "1.21.8"))
        assertNull(MinecraftJavaVersions.pick(emptyList(), "1.20.4"))
    }

    @Test
    fun `says why it chose a runtime`() {
        assertTrue(MinecraftJavaVersions.choose(listOf(21, 26), "1.21.8")!!.reason.contains("minimum"))
        assertTrue(MinecraftJavaVersions.choose(listOf(24, 26), "1.21.8")!!.reason.contains("lowest runtime"))
        assertTrue(MinecraftJavaVersions.choose(listOf(8, 21), "1.16.5")!!.reason.contains("old enough"))
        assertNull(MinecraftJavaVersions.choose(emptyList(), "1.21.8"))
    }

    @Test
    fun `an automatic spec survives being sanitised`() {
        val spec = ServerSpec(software = "paper", version = "1.21.8", javaMajor = 0).sanitised()

        assertEquals(ServerSpec.AUTO_JAVA_MAJOR, spec.javaMajor)

        val pinned = ServerSpec(software = "paper", version = "1.21.8", javaMajor = 17).sanitised()

        assertEquals(17, pinned.javaMajor)
    }
}
