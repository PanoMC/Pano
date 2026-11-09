package com.panomc.platform.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileUploadUtilImageTest {
    @TempDir
    lateinit var dir: File

    private fun file(bytes: ByteArray) = File(dir, "upload").apply { writeBytes(bytes) }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `real images get a fixed extension from their bytes`() {
        assertEquals("image/png" to "png", FileUploadUtil.detectImage(file(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0))))
        assertEquals("image/jpeg" to "jpg", FileUploadUtil.detectImage(file(bytes(0xFF, 0xD8, 0xFF, 0xE0))))
        assertEquals("image/gif" to "gif", FileUploadUtil.detectImage(file("GIF89a....".toByteArray())))
        assertEquals("image/webp" to "webp", FileUploadUtil.detectImage(file("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray())))
        assertEquals("image/x-icon" to "ico", FileUploadUtil.detectImage(file(bytes(0, 0, 1, 0, 1, 0))))
    }

    @Test
    fun `html, svg and scripts are not images`() {
        assertNull(FileUploadUtil.detectImage(file("<html><script>alert(1)</script></html>".toByteArray())))
        assertNull(FileUploadUtil.detectImage(file("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray())))
        assertNull(FileUploadUtil.detectImage(file("alert(1)".toByteArray())))
        assertNull(FileUploadUtil.detectImage(file(ByteArray(0))))
    }

    @Test
    fun `stored files that are not plain images are served as binary`() {
        assertEquals("image/png", MimeTypeUtil.imageMimeTypeOrBinary("favicon.png"))
        assertEquals("application/octet-stream", MimeTypeUtil.imageMimeTypeOrBinary("favicon.html"))
        assertEquals("application/octet-stream", MimeTypeUtil.imageMimeTypeOrBinary("favicon.svg"))
        assertEquals("application/octet-stream", MimeTypeUtil.imageMimeTypeOrBinary("favicon.js"))
    }
}
