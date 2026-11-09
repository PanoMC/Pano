package com.panomc.platform.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.imageio.ImageIO

class SafeImageReaderTest {
    private fun chunk(out: DataOutputStream, type: String, data: ByteArray) {
        out.writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        out.writeInt(crc.value.toInt())
    }

    /** A tiny PNG whose header declares [width] x [height] pixels. */
    private fun bombPng(width: Int, height: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeInt(width); writeInt(height); writeByte(8); writeByte(6); writeByte(0); writeByte(0); writeByte(0)
            }
        }.toByteArray()
        chunk(out, "IHDR", ihdr)
        val deflater = Deflater().apply { setInput(ByteArray(1024)); finish() }
        val buffer = ByteArray(2048)
        val idat = buffer.copyOf(deflater.deflate(buffer))
        chunk(out, "IDAT", idat)
        chunk(out, "IEND", ByteArray(0))
        return bytes.toByteArray()
    }

    @Test
    fun `an image declaring huge dimensions is refused before decoding`() {
        assertNull(SafeImageReader.read(ByteArrayInputStream(bombPng(20000, 20000))))
        assertNull(SafeImageReader.read(ByteArrayInputStream(bombPng(6000, 6000))))
    }

    @Test
    fun `a normal image decodes`() {
        val png = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB), "png", it)
        }.toByteArray()
        val image = SafeImageReader.read(ByteArrayInputStream(png))
        assertNotNull(image)
        assertEquals(64, image!!.width)
        assertEquals(32, image.height)
    }

    @Test
    fun `non images answer null`() {
        assertNull(SafeImageReader.read(ByteArrayInputStream("not an image".toByteArray())))
    }
}
