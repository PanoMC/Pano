package com.panomc.platform.route.api.panel.locale

import com.panomc.platform.route.api.panel.locale.PanelUpdateLocaleTranslationsAPI.Companion.isSafeMarkup
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranslationMarkupTest {
    @Test
    fun `plain text and the formatting the bundled translations use are kept`() {
        listOf(
            "Hello world",
            "5 < 6 and 7 > 3",
            "Read <strong>this</strong> and <b>that</b>.",
            "<strong class=\"me-2\">Note:</strong> line<br />break<br/>",
            "See <a href=\"{href}\">the docs</a>",
            "See <a href=\"https://panomc.com/docs\" target=\"_blank\" rel=\"noopener\">the docs</a>",
            "<mark>found</mark>"
        ).forEach { assertTrue(isSafeMarkup(it), it) }
    }

    @Test
    fun `scripts, handlers and other tags are refused`() {
        listOf(
            "<img src=x onerror=alert(1)>",
            "<img src=x onerror=alert(1)",
            "<script>alert(1)</script>",
            "<b onclick=\"alert(1)\">x</b>",
            "<b/onclick=alert(1)>x</b>",
            "<a href=\"javascript:alert(1)\">x</a>",
            "<a href=\"https://x\" onmouseover=\"alert(1)\">x</a>",
            "<svg onload=alert(1)>",
            "<i<b>mg src=x onerror=alert(1)>",
            "<!-- x --><iframe src=//evil>",
            "<strong class=\"x\" style=\"x\">x</strong>"
        ).forEach { assertFalse(isSafeMarkup(it), it) }
    }
}
