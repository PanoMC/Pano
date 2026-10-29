package com.panomc.platform

import com.panomc.platform.UIManager.Companion.isAndroid
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class UIManagerAndroidTest {
    private fun touch(root: File, path: String) = File(root, path).apply { parentFile.mkdirs(); createNewFile() }

    @Test
    fun `plain linux is not android`(@TempDir root: File) {
        touch(root, "lib/ld-linux-aarch64.so.1")
        assertFalse(isAndroid(root))
    }

    @Test
    fun `termux is android`(@TempDir root: File) {
        touch(root, "system/bin/linker64")
        assertTrue(isAndroid(root))
    }

    @Test
    fun `glibc distro under proot keeps linux build`(@TempDir root: File) {
        touch(root, "system/bin/linker64")
        touch(root, "lib64/ld-linux-x86-64.so.2")
        assertFalse(isAndroid(root))
    }

    @Test
    fun `musl distro under proot keeps linux build`(@TempDir root: File) {
        touch(root, "system/bin/linker64")
        touch(root, "lib/ld-musl-aarch64.so.1")
        assertFalse(isAndroid(root))
    }
}
