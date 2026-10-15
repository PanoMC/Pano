package com.panomc.platform.util

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/** Headless on purpose: a text area needs no window, and no test may open one on the developer's desktop. */
class ConfirmMessageAreaTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun headless() {
            System.setProperty("java.awt.headless", "true")
        }
    }

    private fun clippedRows(message: String): Int {
        val area = UiConsole.messageArea(message)
        val lineHeight = area.getFontMetrics(area.font).height

        // What pack() does: lay the area out at the size it asked for, then see where its text ends.
        val packed = area.preferredSize
        area.size = packed

        val end = area.modelToView2D(area.document.length)
        val neededBottom = end.y + end.height

        return maxOf(0, Math.ceil((neededBottom - packed.height) / lineHeight).toInt())
    }

    @Test
    fun `a long message is fully inside the height the window packs to`() {
        val lines = FirstRunPolicy.warningLines(java.io.File("/srv/pano"), (1..12).map { "entry-$it" }) +
                FirstRunPolicy.skipHintLine()

        assertTrue(clippedRows(lines.joinToString("\n")) == 0)
    }

    @Test
    fun `a long path that wraps is fully inside the height too`() {
        val dir = java.io.File("C:/Users/someone/Downloads/" + "a-long-folder-name/".repeat(4))
        val lines = FirstRunPolicy.warningLines(dir, listOf("notes.txt"))

        assertTrue(clippedRows(lines.joinToString("\n")) == 0)
    }
}
