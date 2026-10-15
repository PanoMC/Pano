package com.panomc.platform.gate

import com.panomc.platform.ApiLevel
import com.panomc.platform.error.FailedToInstallResource
import com.panomc.platform.ui.ThemeApiLevelUnsupported
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The install-time refusal: no level (0), a lower level, a higher level, and the levels of this build. */
class InstallApiLevelTest {
    @Test
    fun `a plugin without a level or below the minimum is refused with the range in the message`() {
        val none = assertThrows(FailedToInstallResource::class.java) { InstallApiLevel.requireCompatiblePlugin("p", 0, 2, 3) }
        val message = none.message.toString()

        assertTrue("API level 0" in message && "2 to 3" in message, message)
        assertTrue("TOO_OLD" in none.encode(mapOf()), none.encode(mapOf()))
        assertThrows(FailedToInstallResource::class.java) { InstallApiLevel.requireCompatiblePlugin("p", 1, 2, 3) }
    }

    @Test
    fun `a plugin above the current level is refused as too new, inside the range passes`() {
        val tooNew = assertThrows(FailedToInstallResource::class.java) { InstallApiLevel.requireCompatiblePlugin("p", 4, 2, 3) }

        assertTrue("TOO_NEW" in tooNew.encode(mapOf()), tooNew.encode(mapOf()))
        InstallApiLevel.requireCompatiblePlugin("p", 2, 2, 3)
        InstallApiLevel.requireCompatiblePlugin("p", 3, 2, 3)
    }

    @Test
    fun `a theme without, below or above the range is refused with THEME_API_LEVEL_UNSUPPORTED`() {
        val none = assertThrows(ThemeApiLevelUnsupported::class.java) { InstallApiLevel.requireCompatibleTheme("t", 0, 2, 3) }

        assertEquals("THEME_API_LEVEL_UNSUPPORTED", none.code)
        assertThrows(ThemeApiLevelUnsupported::class.java) { InstallApiLevel.requireCompatibleTheme("t", 1, 2, 3) }

        val tooNew = assertThrows(ThemeApiLevelUnsupported::class.java) { InstallApiLevel.requireCompatibleTheme("t", 4, 2, 3) }

        assertTrue("TOO_NEW" in tooNew.encode(mapOf()), tooNew.encode(mapOf()))
        InstallApiLevel.requireCompatibleTheme("t", 3, 2, 3)
    }

    @Test
    fun `the levels of this build are accepted and the level after it is not`() {
        InstallApiLevel.requireCompatiblePlugin("p", ApiLevel.CURRENT)
        InstallApiLevel.requireCompatibleTheme("t", ApiLevel.MIN_SUPPORTED)
        assertThrows(FailedToInstallResource::class.java) { InstallApiLevel.requireCompatiblePlugin("p", ApiLevel.CURRENT + 1) }
    }
}
