package com.panomc.platform.ui

import com.panomc.platform.UIManager.Companion.parseInstalledThemeText
import com.panomc.platform.UIManager.Companion.servedThemeId
import com.panomc.platform.UIManager.Companion.themeVerdict
import com.panomc.platform.gate.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Upgrade case: the manifest.json of a theme laid down by a released Pano (screenshots as an array, no apiLevel) must
 * be read as a level 0 theme, so the gate refuses it and the bundled theme is served instead of starting it unchecked.
 */
class ThemeApiLevelGateTest {
    private val oldBlaze = """
        { "id": "blaze-theme", "title": "Blaze", "version": "v1.1.0", "author": "Pano", "panoVersion": "local-build",
          "screenshots": ["screenshots/1.png", "screenshots/2.png"], "premium": false,
          "hash": "ab", "createdAt": 1, "updatedAt": 1, "installedBy": "SYSTEM" }
    """

    private val current = """
        { "id": "blaze-theme", "title": "Blaze", "version": "v2.0.0", "author": "Pano", "panoVersion": "x",
          "screenshots": { "screenshots/1.png": "cd" }, "apiLevel": 1,
          "hash": "ab", "createdAt": 1, "updatedAt": 1, "installedBy": "USER" }
    """

    @Test
    fun `an old manifest with array screenshots still reads and is too old`() {
        val theme = parseInstalledThemeText(oldBlaze)

        assertEquals(0, theme.apiLevel)
        assertEquals(setOf("screenshots/1.png", "screenshots/2.png"), theme.screenshots.keys)
        assertEquals(Verdict.TOO_OLD, themeVerdict("blaze-theme", listOf(theme)))
    }

    @Test
    fun `a refused theme is replaced by the bundled one wherever a theme is started`() {
        val installed = listOf(parseInstalledThemeText(oldBlaze))

        assertEquals("vanilla-theme", servedThemeId("blaze-theme", installed))
        assertEquals("vanilla-theme", servedThemeId("vanilla-theme", installed))
    }

    @Test
    fun `a compatible theme is started as asked`() {
        assertEquals("blaze-theme", servedThemeId("blaze-theme", listOf(parseInstalledThemeText(current))))
    }

    @Test
    fun `activating an incompatible theme is refused with the code the panel shows, the bundled and a compatible one pass`() {
        val installed = listOf(parseInstalledThemeText(oldBlaze), parseInstalledThemeText(current.replace("blaze-theme", "ok-theme")))

        val error = org.junit.jupiter.api.Assertions.assertThrows(ThemeApiLevelUnsupported::class.java) {
            requireCompatibleTheme("blaze-theme", installed)
        }

        assertEquals("THEME_API_LEVEL_UNSUPPORTED", error.code)

        requireCompatibleTheme("ok-theme", installed)
        requireCompatibleTheme("vanilla-theme", installed)
    }
}
