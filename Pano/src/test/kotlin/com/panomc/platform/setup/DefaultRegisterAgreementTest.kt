package com.panomc.platform.setup

import com.panomc.platform.AppConstants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultRegisterAgreementTest {
    @Test
    fun `every setup language has its own agreement`() {
        val texts = AppConstants.AVAILABLE_LOCALES.associateWith { DefaultRegisterAgreement.forLocale(it) }

        texts.forEach { (locale, text) -> assertTrue(text.startsWith("<section>"), "missing agreement for $locale") }
        assertEquals(texts.size, texts.values.toSet().size, "languages share one text")
        assertTrue(texts.getValue("tr").contains("Kayıt Sözleşmesi"))
    }

    @Test
    fun `a region code falls back to its language, an unknown one to English`() {
        assertEquals(DefaultRegisterAgreement.forLocale("tr"), DefaultRegisterAgreement.forLocale("tr-TR"))
        assertEquals(DefaultRegisterAgreement.forLocale("en-US"), DefaultRegisterAgreement.forLocale("de"))
    }
}
