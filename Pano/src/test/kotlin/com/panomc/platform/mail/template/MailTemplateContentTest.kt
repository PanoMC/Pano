package com.panomc.platform.mail.template

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MailTemplateContentTest {
    @Test
    fun `reads every header key literally and the body after the header`() {
        val content = MailTemplateContent.parse(
            """
            ---
            subject: {{websiteName}} — Activate: now
            preheader: Finish signing up
            buttonLabel: Activate
            buttonUrl: {{activationLink}}
            footer: Ignore this if it was not you.
            ---
            <p>Hello <strong>{{username}}</strong>,</p>
            <p>Second line</p>
            """.trimIndent()
        )

        assertEquals(MailTemplateContent.Mode.FIELDS, content.mode)
        assertEquals("{{websiteName}} — Activate: now", content.subject)
        assertEquals("Finish signing up", content.preheader)
        assertEquals("Activate", content.buttonLabel)
        assertEquals("{{activationLink}}", content.buttonUrl)
        assertEquals("Ignore this if it was not you.", content.footer)
        assertEquals("<p>Hello <strong>{{username}}</strong>,</p>\n<p>Second line</p>", content.body)
    }

    @Test
    fun `missing optional keys are empty and the mode defaults to fields`() {
        val content = MailTemplateContent.parse("---\nsubject: Hi\n---\nBody")

        assertEquals("Hi", content.subject)
        assertEquals("", content.preheader)
        assertEquals("", content.buttonUrl)
        assertEquals("", content.footer)
        assertEquals("Body", content.body)
    }

    @Test
    fun `mode html is read case-insensitively`() {
        val content = MailTemplateContent.parse("---\nsubject: S\nmode: HTML\n---\n<html><body>x</body></html>")

        assertEquals(MailTemplateContent.Mode.HTML, content.mode)
        assertEquals("<html><body>x</body></html>", content.body)
    }

    @Test
    fun `CRLF files and a BOM parse the same`() {
        val content = MailTemplateContent.parse("\uFEFF---\r\nsubject: S\r\nfooter: F\r\n---\r\n<p>b</p>\r\n")

        assertEquals("S", content.subject)
        assertEquals("F", content.footer)
        assertEquals("<p>b</p>", content.body)
    }

    @Test
    fun `a file without a header is all body`() {
        val content = MailTemplateContent.parse("<p>only a body</p>")

        assertEquals("", content.subject)
        assertEquals("<p>only a body</p>", content.body)
    }

    @Test
    fun `an unclosed header or an unknown mode is an error`() {
        assertThrows<IllegalArgumentException> { MailTemplateContent.parse("---\nsubject: S\n<p>b</p>") }
        assertThrows<IllegalArgumentException> { MailTemplateContent.parse("---\nsubject: S\nmode: pdf\n---\nb") }
        assertThrows<IllegalArgumentException> { MailTemplateContent.parse("---\nno colon here\n---\nb") }
    }
}
