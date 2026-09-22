package com.panomc.platform.server.files

import com.panomc.platform.route.api.panel.server.files.PanelDownloadServerFileAPI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InlinePreviewTypesTest {
    @Test
    fun `previews images, video and audio by extension`() {
        assertEquals("image/png", InlinePreviewTypes.contentTypeOf("plugins/icon.png"))
        assertEquals("image/jpeg", InlinePreviewTypes.contentTypeOf("a/b/Screenshot.JPG"))
        assertEquals("image/jpeg", InlinePreviewTypes.contentTypeOf("photo.jpeg"))
        assertEquals("image/avif", InlinePreviewTypes.contentTypeOf("x.avif"))
        assertEquals("video/mp4", InlinePreviewTypes.contentTypeOf("clips/intro.mp4"))
        assertEquals("video/quicktime", InlinePreviewTypes.contentTypeOf("clip.mov"))
        assertEquals("audio/mpeg", InlinePreviewTypes.contentTypeOf("music/theme.mp3"))
        assertEquals("audio/ogg", InlinePreviewTypes.contentTypeOf("sounds/click.ogg"))
        assertEquals("audio/opus", InlinePreviewTypes.contentTypeOf("voice.opus"))
    }

    @Test
    fun `never previews anything that can run script`() {
        assertNull(InlinePreviewTypes.contentTypeOf("logo.svg"))
        assertNull(InlinePreviewTypes.contentTypeOf("index.html"))
        assertNull(InlinePreviewTypes.contentTypeOf("page.htm"))
        assertNull(InlinePreviewTypes.contentTypeOf("image.png.html"))
        assertNull(InlinePreviewTypes.contentTypeOf("server.properties"))
        assertNull(InlinePreviewTypes.contentTypeOf("png"))
        assertNull(InlinePreviewTypes.contentTypeOf("folder.png/readme"))
        assertFalse(InlinePreviewTypes.isPreviewable("script.js"))
        assertTrue(InlinePreviewTypes.isPreviewable("a.webp"))
    }

    @Test
    fun `names a zip after the one path, the base directory or the root`() {
        assertEquals("world.zip", PanelDownloadServerFileAPI.archiveFileName(listOf("world"), ""))
        assertEquals("Essentials.zip", PanelDownloadServerFileAPI.archiveFileName(listOf("plugins/Essentials"), "plugins"))
        assertEquals("plugins.zip", PanelDownloadServerFileAPI.archiveFileName(listOf("plugins/a.jar", "plugins/b.jar"), "plugins"))
        assertEquals("files.zip", PanelDownloadServerFileAPI.archiveFileName(listOf("world", "server.properties"), ""))
    }

    @Test
    fun `defaults the base to the first path's directory and keeps every path inside it`() {
        assertEquals("", PanelDownloadServerFileAPI.defaultBase("world"))
        assertEquals("plugins", PanelDownloadServerFileAPI.defaultBase("plugins/a.jar"))

        assertTrue(PanelDownloadServerFileAPI.isInside("", "world"))
        assertTrue(PanelDownloadServerFileAPI.isInside("plugins", "plugins/a.jar"))
        assertFalse(PanelDownloadServerFileAPI.isInside("plugins", "plugins"))
        assertFalse(PanelDownloadServerFileAPI.isInside("plugins", "pluginsX/a.jar"))
        assertFalse(PanelDownloadServerFileAPI.isInside("plugins", "world/level.dat"))
    }
}
