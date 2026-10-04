package com.panomc.platform.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import javax.imageio.ImageIO

/**
 * The server icon pipeline: whatever picture arrives leaves as the 64×64 PNG Minecraft reads, or
 * not at all.
 */
class ServerIconImageTest {
    private fun image(width: Int, height: Int, type: Int = BufferedImage.TYPE_INT_RGB, paint: (BufferedImage) -> Unit) =
        BufferedImage(width, height, type).also(paint)

    private fun encode(image: BufferedImage, format: String): ByteArray =
        ByteArrayOutputStream().also { assertTrue(ImageIO.write(image, format, it), format) }.toByteArray()

    private fun decode(png: ByteArray): BufferedImage =
        ImageIO.read(ByteArrayInputStream(png)).also { assertNotNull(it, "the icon decodes") }!!

    /** Three stripes of [first], [second] and [third] along the long side. */
    private fun stripes(width: Int, height: Int, first: Color, second: Color, third: Color) = image(width, height) { img ->
        img.createGraphics().apply {
            if (width >= height) {
                val third1 = width / 3
                color = first; fillRect(0, 0, third1, height)
                color = second; fillRect(third1, 0, third1, height)
                color = third; fillRect(2 * third1, 0, width - 2 * third1, height)
            } else {
                val third1 = height / 3
                color = first; fillRect(0, 0, width, third1)
                color = second; fillRect(0, third1, width, third1)
                color = third; fillRect(0, 2 * third1, width, height - 2 * third1)
            }
            dispose()
        }
    }

    private fun isGreenish(rgb: Int): Boolean {
        val color = Color(rgb, true)

        return color.green > 200 && color.red < 60 && color.blue < 60
    }

    @Test
    fun `a wide picture keeps its middle square, at exactly 64 by 64, as a PNG`() {
        val icon = ServerIconImage.toServerIcon(encode(stripes(300, 100, Color.RED, Color.GREEN, Color.BLUE), "png"))

        assertTrue(icon.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)), "a PNG")

        val decoded = decode(icon)

        assertEquals(64, decoded.width)
        assertEquals(64, decoded.height)

        // The red and blue thirds were cropped away: every edge of the square is the green middle.
        listOf(0 to 32, 63 to 32, 32 to 0, 32 to 63, 32 to 32).forEach { (x, y) ->
            assertTrue(isGreenish(decoded.getRGB(x, y)), "pixel $x,$y")
        }
    }

    @Test
    fun `a tall picture is cropped the other way`() {
        val decoded = decode(ServerIconImage.toServerIcon(encode(stripes(90, 270, Color.RED, Color.GREEN, Color.BLUE), "png")))

        assertTrue(isGreenish(decoded.getRGB(32, 1)))
        assertTrue(isGreenish(decoded.getRGB(32, 62)))
    }

    @Test
    fun `jpeg and gif come out as a 64 by 64 PNG too`() {
        listOf("jpeg", "gif").forEach { format ->
            val decoded = decode(ServerIconImage.toServerIcon(encode(stripes(200, 200, Color.RED, Color.GREEN, Color.BLUE), format)))

            assertEquals(64, decoded.width, format)
            assertEquals(64, decoded.height, format)
        }
    }

    @Test
    fun `webp is read, which the JDK cannot do on its own`() {
        // 1×1 lossy and 1×1 lossless WebP, the smallest real files of each kind.
        listOf(
            "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA",
            "UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA=="
        ).forEach { sample ->
            val decoded = decode(ServerIconImage.toServerIcon(Base64.getDecoder().decode(sample)))

            assertEquals(64, decoded.width)
            assertEquals(64, decoded.height)
        }
    }

    @Test
    fun `transparency survives`() {
        val transparent = image(128, 128, BufferedImage.TYPE_INT_ARGB) { img ->
            img.createGraphics().apply {
                color = Color(255, 0, 0, 255)
                fillRect(0, 0, 64, 128)
                dispose()
            }
        }

        val decoded = decode(ServerIconImage.toServerIcon(encode(transparent, "png")))

        assertEquals(0, Color(decoded.getRGB(60, 32), true).alpha, "the transparent half stays transparent")
        assertEquals(255, Color(decoded.getRGB(4, 32), true).alpha)
    }

    @Test
    fun `nothing of the original's metadata comes through`() {
        val icon = ServerIconImage.toServerIcon(encode(stripes(200, 100, Color.RED, Color.GREEN, Color.BLUE), "jpeg"))

        val chunks = mutableListOf<String>()
        val buffer = ByteBuffer.wrap(icon, 8, icon.size - 8)

        while (buffer.remaining() >= 12) {
            val length = buffer.int
            val type = ByteArray(4).also { buffer.get(it) }.toString(Charsets.US_ASCII)

            chunks.add(type)
            buffer.position(buffer.position() + length + 4)
        }

        assertEquals("IHDR", chunks.first())
        assertEquals("IEND", chunks.last())
        assertTrue(chunks.none { it in setOf("tEXt", "zTXt", "iTXt", "eXIf", "tIME") }, chunks.toString())
    }

    @Test
    fun `an upload over two megabytes is refused before it is decoded`() {
        val invalid = assertThrows(ServerIconImage.InvalidImage::class.java) {
            ServerIconImage.toServerIcon(ByteArray((ServerIconImage.MAX_BYTES + 1).toInt()))
        }

        assertTrue(invalid.message!!.contains("MB"), invalid.message)
    }

    @Test
    fun `anything that is not an image is refused`() {
        listOf(ByteArray(0), "not an image at all".toByteArray(), ByteArray(4096) { it.toByte() }).forEach { bytes ->
            assertThrows(ServerIconImage.InvalidImage::class.java) { ServerIconImage.toServerIcon(bytes) }
        }
    }

    @Test
    fun `an image format Minecraft's icon cannot come from is refused`() {
        val bmp = encode(stripes(64, 64, Color.RED, Color.GREEN, Color.BLUE), "bmp")

        val invalid = assertThrows(ServerIconImage.InvalidImage::class.java) { ServerIconImage.toServerIcon(bmp) }

        assertTrue(invalid.message!!.contains("PNG, JPEG, WebP and GIF"), invalid.message)
    }

    @Test
    fun `a small file that claims an enormous picture is refused before its pixels are read`() {
        // 5000×5000 one-bit pixels compress to a few kilobytes but would decode to 25 million.
        val bomb = encode(BufferedImage(5000, 5000, BufferedImage.TYPE_BYTE_BINARY), "png")

        assertTrue(bomb.size < ServerIconImage.MAX_BYTES)

        val invalid = assertThrows(ServerIconImage.InvalidImage::class.java) { ServerIconImage.toServerIcon(bomb) }

        assertTrue(invalid.message!!.contains("too large"), invalid.message)
    }
}
