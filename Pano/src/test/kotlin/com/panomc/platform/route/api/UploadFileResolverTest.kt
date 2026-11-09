package com.panomc.platform.route.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class UploadFileResolverTest {
    @TempDir
    lateinit var root: File

    private fun setup(): File {
        File(root, "config.conf").writeText("secret")
        val folder = File(root, "file-uploads/theme-settings").apply { mkdirs() }
        File(folder, "0b1c2d3e-aaaa-bbbb.png").writeText("png")
        File(folder, "sub").mkdirs()
        return folder
    }

    @Test
    fun `a plain file name inside the folder resolves`() {
        val folder = setup()
        assertEquals(
            File(folder, "0b1c2d3e-aaaa-bbbb.png").canonicalFile,
            UploadFileResolver.resolve(folder.path, "0b1c2d3e-aaaa-bbbb.png")
        )
    }

    @Test
    fun `traversal and separators are refused`() {
        val folder = setup()
        listOf(
            "../../config.conf",
            "..\\..\\config.conf",
            "..",
            ".",
            "",
            "sub/../0b1c2d3e-aaaa-bbbb.png",
            "/etc/passwd",
            "C:\\Windows\\win.ini",
            "C:config.conf",
            "a\u0000.png",
            "%2e%2e%2fconfig.conf",
            ".hidden"
        ).forEach { assertNull(UploadFileResolver.resolve(folder.path, it), it) }
    }

    @Test
    fun `missing files and directories are refused`() {
        val folder = setup()
        assertNull(UploadFileResolver.resolve(folder.path, "missing.png"))
        assertNull(UploadFileResolver.resolve(folder.path, "sub"))
    }
}
