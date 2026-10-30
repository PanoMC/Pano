package com.panomc.platform.db

import com.panomc.platform.db.MariaDBManager.Companion.isMusl
import com.panomc.platform.db.MariaDBManager.Companion.portableTarget
import com.panomc.platform.util.OperatingSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MariaDBManagerTargetTest {
    private fun touch(root: File, path: String) = File(root, path).apply { parentFile.mkdirs(); createNewFile() }

    @Test
    fun `windows and macos targets`(@TempDir root: File) {
        assertEquals("windows-x64", portableTarget(OperatingSystem.WINDOWS, "amd64", root))
        assertEquals("windows-arm64", portableTarget(OperatingSystem.WINDOWS, "aarch64", root))
        assertEquals("windows-x86", portableTarget(OperatingSystem.WINDOWS, "x86", root))
        assertEquals("macos-arm64", portableTarget(OperatingSystem.DARWIN, "aarch64", root))
        assertEquals("macos-x64", portableTarget(OperatingSystem.DARWIN, "x86_64", root))
        assertNull(portableTarget(OperatingSystem.DARWIN, "x86", root))
    }

    @Test
    fun `glibc linux targets`(@TempDir root: File) {
        touch(root, "lib64/ld-linux-x86-64.so.2")
        assertEquals("linux-x64", portableTarget(OperatingSystem.LINUX, "amd64", root))
        assertEquals("linux-arm64", portableTarget(OperatingSystem.LINUX, "aarch64", root))
        assertEquals("linux-x86", portableTarget(OperatingSystem.LINUX, "i386", root))
        assertEquals("linux-armv7", portableTarget(OperatingSystem.LINUX, "arm", root))
        assertEquals("linux-ppc64le", portableTarget(OperatingSystem.LINUX, "ppc64le", root))
        assertEquals("linux-s390x", portableTarget(OperatingSystem.LINUX, "s390x", root))
        assertEquals("linux-riscv64", portableTarget(OperatingSystem.LINUX, "riscv64", root))
        assertNull(portableTarget(OperatingSystem.LINUX, "sparcv9", root))
    }

    @Test
    fun `musl linux targets`(@TempDir root: File) {
        touch(root, "lib/ld-musl-x86_64.so.1")
        assertTrue(isMusl(root))
        assertEquals("linux-musl-x64", portableTarget(OperatingSystem.LINUX, "amd64", root))
        assertEquals("linux-musl-armv7", portableTarget(OperatingSystem.LINUX, "arm", root))
        assertNull(portableTarget(OperatingSystem.LINUX, "s390x", root))
    }

    @Test
    fun `glibc with a musl loader next to it stays glibc`(@TempDir root: File) {
        touch(root, "lib/ld-linux-aarch64.so.1")
        touch(root, "lib/ld-musl-aarch64.so.1")
        assertFalse(isMusl(root))
    }

    @Test
    fun `android has no package`(@TempDir root: File) {
        touch(root, "system/bin/linker64")
        assertNull(portableTarget(OperatingSystem.LINUX, "aarch64", root))
    }
}
