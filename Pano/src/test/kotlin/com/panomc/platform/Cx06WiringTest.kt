package com.panomc.platform

import com.panomc.platform.UIManager.Companion.InstalledBy
import com.panomc.platform.UIManager.Companion.InstalledTheme
import com.panomc.platform.node.NodeProtocol
import com.panomc.platform.route.api.panel.frontend.mode.shouldRefreshDescriptor
import com.panomc.platform.route.api.panel.settings.shouldReapplyFrontend
import com.panomc.platform.route.api.panel.webhook.PanelTestWebhookAPI
import com.panomc.platform.server.ServerProtocol
import com.panomc.platform.ui.FrontendMode
import com.panomc.platform.ui.ThemeApiLevelUnsupported
import com.panomc.platform.ui.requireCompatibleTheme
import com.panomc.platform.util.UsageMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pure parts of CX-06: descriptor refresh and dev-mode re-apply rules, the theme level refusal, the limiter, the constants. */
class Cx06WiringTest {
    @Test
    fun `the descriptor is refreshed only in EXTERNAL or NONE mode and only when its source changed`() {
        assertTrue(shouldRefreshDescriptor(FrontendMode.EXTERNAL, "", "http://a", "", "http://b"))
        assertTrue(shouldRefreshDescriptor(FrontendMode.NONE, "http://a/d.json", "", "http://b/d.json", ""))
        assertFalse(shouldRefreshDescriptor(FrontendMode.EXTERNAL, "", "http://a", "", "http://a"))
        assertFalse(shouldRefreshDescriptor(FrontendMode.THEME, "", "http://a", "", "http://b"))
        assertFalse(shouldRefreshDescriptor(FrontendMode.CUSTOM_APP, "x", "", "y", ""))
    }

    @Test
    fun `development mode re-applies the front-end only for a theme on a site`() {
        assertTrue(shouldReapplyFrontend(true, FrontendMode.THEME, UsageMode.WEBSITE))
        assertTrue(shouldReapplyFrontend(true, FrontendMode.THEME, UsageMode.BOTH))
        assertFalse(shouldReapplyFrontend(true, FrontendMode.THEME, UsageMode.SERVERS))
        assertFalse(shouldReapplyFrontend(true, FrontendMode.EXTERNAL, UsageMode.WEBSITE))
        assertFalse(shouldReapplyFrontend(false, FrontendMode.THEME, UsageMode.WEBSITE))
    }

    private fun theme(id: String, level: Int) = InstalledTheme(
        id, id, "", "1.0.0", "", "", "", "", emptyMap(), "h", 0, 0, InstalledBy.USER, false, null, level
    )

    @Test
    fun `a theme outside the supported level is refused, the bundled one is exempt`() {
        val installed = listOf(theme("old", 0), theme("new", ApiLevel.CURRENT + 1), theme("fine", ApiLevel.CURRENT), theme(AppConstants.DEFAULT_THEME_ID, 0))

        val tooOld = assertThrows(ThemeApiLevelUnsupported::class.java) { requireCompatibleTheme("old", installed) }
        assertEquals("THEME_API_LEVEL_UNSUPPORTED", tooOld.code)

        assertThrows(ThemeApiLevelUnsupported::class.java) { requireCompatibleTheme("new", installed) }
        requireCompatibleTheme("fine", installed)
        requireCompatibleTheme(AppConstants.DEFAULT_THEME_ID, installed)
        requireCompatibleTheme("unknown", installed)
    }

    @Test
    fun `the test button allows ten a minute per user`() {
        var now = 1_000L
        val limiter = PanelTestWebhookAPI.testLimiter { now }

        repeat(10) { assertTrue(limiter.tryAcquire("user:1")) }
        assertFalse(limiter.tryAcquire("user:1"))
        assertTrue(limiter.tryAcquire("user:2"))

        now += 59_999
        assertFalse(limiter.tryAcquire("user:1"))

        now += 1
        assertTrue(limiter.tryAcquire("user:1"))
    }

    @Test
    fun `the protocol constants agree with what the API level demands`() {
        assertEquals(6, NodeProtocol.VERSION)
        assertEquals(3, ServerProtocol.CURRENT_PROTOCOL_VERSION)
        assertEquals(ApiLevel.MIN_NODE_PROTOCOL, NodeProtocol.VERSION)
        assertEquals(ApiLevel.MIN_MC_PROTOCOL, ServerProtocol.CURRENT_PROTOCOL_VERSION)
    }
}
