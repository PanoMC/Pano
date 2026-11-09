package com.panomc.platform.util

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Decodes an uploaded image only when its declared size is reasonable.
 *
 * `ImageIO.read` allocates width * height pixels from the header before it looks at the data, so a file of a few
 * kilobytes that declares e.g. 20000x20000 pixels needs gigabytes of heap and ends in an OutOfMemoryError. The header
 * is read first here, and anything over [MAX_SIDE] or [MAX_PIXELS] is refused before decoding.
 */
object SafeImageReader {
    const val MAX_SIDE = 10_000
    const val MAX_PIXELS = 25_000_000L

    fun isAcceptableSize(width: Int, height: Int): Boolean =
        width in 1..MAX_SIDE && height in 1..MAX_SIDE && width.toLong() * height.toLong() <= MAX_PIXELS

    /**
     * Decodes [source] (a File, an InputStream or anything else `ImageIO.createImageInputStream` takes). Answers null
     * when it is not a readable image or its dimensions are too large.
     */
    fun read(source: Any): BufferedImage? {
        val stream = try {
            ImageIO.createImageInputStream(source)
        } catch (_: Exception) {
            null
        } ?: return null

        stream.use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) {
                return null
            }

            val reader = readers.next()

            return try {
                reader.setInput(input, true, true)

                if (!isAcceptableSize(reader.getWidth(0), reader.getHeight(0))) {
                    null
                } else {
                    reader.read(0, reader.defaultReadParam)
                }
            } catch (_: Exception) {
                null
            } finally {
                reader.dispose()
            }
        }
    }
}
