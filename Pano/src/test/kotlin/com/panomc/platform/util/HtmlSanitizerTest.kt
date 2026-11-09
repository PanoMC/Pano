package com.panomc.platform.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HtmlSanitizerTest {
    @Test
    fun `editor output is kept as written`() {
        val html = "<h2>Title</h2><p>Hello <strong>bold</strong> <em>it</em> <u>u</u> <s>s</s></p>" +
                "<ul><li><p>one</p></li></ul><ol start=\"3\"><li><p>two</p></li></ol>" +
                "<blockquote><p>quote</p></blockquote><pre><code class=\"language-kotlin\">val x = 1</code></pre><hr>" +
                "<p><span style=\"color: #ff0000; font-size: 18px\">red</span></p>" +
                "<p><a target=\"_blank\" rel=\"noopener noreferrer nofollow\" href=\"https://panomc.com/x\">link</a></p>" +
                "<img src=\"https://example.com/a.png\" alt=\"a\">"

        assertEquals(html, HtmlSanitizer.sanitize(html))
    }

    @Test
    fun `relative links and images stay`() {
        val html = "<p><a href=\"/posts/foo-1\">x</a><img src=\"/api/v1/posts/thumbnails/a.png\"></p>"

        assertEquals(html, HtmlSanitizer.sanitize(html))
    }

    @Test
    fun `scripts and event handlers are removed`() {
        val out = HtmlSanitizer.sanitize(
            "<img src=x onerror=alert(1)><script>alert(1)</script><p onclick=\"x()\">t</p>" +
                    "<iframe src=\"https://evil\"></iframe><svg onload=alert(1)></svg>"
        )

        assertFalse(out.contains("onerror", true))
        assertFalse(out.contains("script", true))
        assertFalse(out.contains("onclick", true))
        assertFalse(out.contains("iframe", true))
        assertFalse(out.contains("svg", true))
        assertTrue(out.contains("<p>t</p>"))
    }

    @Test
    fun `dangerous urls are removed`() {
        val out = HtmlSanitizer.sanitize(
            "<a href=\"javascript:alert(1)\">a</a><a href=\"JaVaScRiPt:alert(1)\">b</a>" +
                    "<img src=\"data:image/svg+xml;base64,PHN2Zz4=\"><a href=\"data:text/html,x\">c</a>"
        )

        assertFalse(out.contains("javascript", true))
        assertFalse(out.contains("data:", true))
    }

    @Test
    fun `only text style declarations are kept`() {
        val out = HtmlSanitizer.sanitize(
            "<span style=\"position: fixed; top: 0; color: rgb(1, 2, 3); background: url(javascript:x); " +
                    "font-family: 'Comic Sans', serif\">t</span>"
        )

        assertEquals("<span style=\"color: rgb(1, 2, 3); font-family: 'Comic Sans', serif\">t</span>", out)
    }

    @Test
    fun `target other than blank is dropped`() {
        assertEquals("<a href=\"https://a.b\">x</a>", HtmlSanitizer.sanitize("<a href=\"https://a.b\" target=\"_top\">x</a>"))
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals("", HtmlSanitizer.sanitize(""))
        assertEquals("", HtmlSanitizer.sanitize(null))
    }
}
