package com.panomc.platform.util

import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpHeaders
import io.vertx.ext.web.RoutingContext
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

object PlaceholderUtil {
    fun generateImage(width: Int, height: Int, text: String): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()

        g.color = Color(0xDDDDDD)
        g.fillRect(0, 0, width, height)

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)

        g.color = Color.GRAY
        g.font = Font("SansSerif", Font.PLAIN, minOf(width, height) / 5)

        val metrics = g.fontMetrics
        val x = (width - metrics.stringWidth(text)) / 2
        val y = (height - metrics.height) / 2 + metrics.ascent
        g.drawString(text, x, y)

        g.dispose()

        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    fun sendPlaceholder(context: RoutingContext, imageBytes: ByteArray) {
        context.response()
            .putHeader(HttpHeaders.CONTENT_TYPE, "image/png")
            .putHeader(HttpHeaders.CACHE_CONTROL, "no-cache")
            .end(Buffer.buffer(imageBytes))
    }

}