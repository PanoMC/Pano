package com.panomc.platform.server

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi
import org.imgscalr.Scalr
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.spi.IIORegistry

/**
 * Turns an uploaded picture into the `server-icon.png` Minecraft will read (the panel header's icon
 * upload).
 *
 * Minecraft only accepts a 64×64 PNG, so whatever arrives — a PNG, a JPEG, a WebP or the first frame
 * of a GIF, at most [MAX_BYTES] — is decoded, center-cropped to a square, scaled to exactly
 * [SIZE]×[SIZE] and encoded again as a fresh PNG. Re-encoding from pixels is also what strips every
 * piece of metadata the original carried (EXIF, GPS, comments): nothing of the upload survives but
 * the picture.
 *
 * Anything that does not decode as one of those four formats is refused, and so is an image that
 * claims more than [MAX_PIXELS] pixels — a few kilobytes of PNG can declare a picture that would
 * take gigabytes to decode, so the size is read from the header before a single pixel is.
 */
object ServerIconImage {
    /** The one size Minecraft reads a server icon at. */
    const val SIZE = 64

    /** Largest upload accepted. */
    const val MAX_BYTES = 2L * 1024L * 1024L

    /** Largest picture decoded: 4096 × 4096, far beyond any sensible icon source. */
    const val MAX_PIXELS = 4096L * 4096L

    /** Formats accepted, by the name their ImageIO reader reports. */
    private val FORMATS = setOf("png", "jpeg", "jpg", "gif", "webp")

    /** Why an upload was refused. */
    class InvalidImage(message: String) : IllegalArgumentException(message)

    init {
        // The fat jar keeps one META-INF/services file per name, so the WebP reader is registered by
        // hand rather than trusted to be discovered.
        IIORegistry.getDefaultInstance().registerServiceProvider(WebPImageReaderSpi())
    }

    /** [bytes] as a 64×64 PNG, or [InvalidImage] with the reason it is not one. */
    fun toServerIcon(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) {
            throw InvalidImage("The file is empty.")
        }

        if (bytes.size > MAX_BYTES) {
            throw InvalidImage("The image is larger than ${MAX_BYTES / (1024 * 1024)} MB.")
        }

        val decoded = decode(bytes)

        val square = centerSquare(decoded)

        val scaled = Scalr.resize(square, Scalr.Method.ULTRA_QUALITY, Scalr.Mode.FIT_EXACT, SIZE, SIZE)

        // Drawn onto a plain ARGB canvas so transparency survives and the PNG writer is handed a
        // type it always supports, whatever colour model the source decoded into.
        val icon = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)

        icon.createGraphics().apply {
            drawImage(scaled, 0, 0, SIZE, SIZE, null)
            dispose()
        }

        val out = ByteArrayOutputStream()

        if (!ImageIO.write(icon, "png", out)) {
            throw InvalidImage("The icon could not be encoded.")
        }

        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): BufferedImage {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
            ?: throw InvalidImage("The file is not an image.")

        input.use { stream ->
            val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull()
                ?: throw InvalidImage("The file is not an image.")

            try {
                val format = reader.formatName.lowercase()

                if (format !in FORMATS) {
                    throw InvalidImage("Only PNG, JPEG, WebP and GIF images can be used as a server icon.")
                }

                reader.input = stream

                val width = reader.getWidth(0).toLong()
                val height = reader.getHeight(0).toLong()

                if (width <= 0 || height <= 0) {
                    throw InvalidImage("The file is not an image.")
                }

                if (width * height > MAX_PIXELS) {
                    throw InvalidImage("The image is too large to use as an icon ($width×$height).")
                }

                return reader.read(0) ?: throw InvalidImage("The file is not an image.")
            } catch (invalid: InvalidImage) {
                throw invalid
            } catch (_: Exception) {
                throw InvalidImage("The file is not an image.")
            } finally {
                reader.dispose()
            }
        }
    }

    /** The largest centered square of [image]. */
    private fun centerSquare(image: BufferedImage): BufferedImage {
        val side = minOf(image.width, image.height)

        if (image.width == side && image.height == side) {
            return image
        }

        return image.getSubimage((image.width - side) / 2, (image.height - side) / 2, side, side)
    }
}
