package com.panomc.platform.setup

/**
 * The registration agreement a new Pano starts with, bundled per language under
 * `register-agreement/<locale>.html`. The setup wizard writes it in the language chosen there;
 * an unknown language falls back to the plain language code (`tr-TR` → `tr`), then to en-US.
 */
object DefaultRegisterAgreement {
    private const val FALLBACK_LOCALE = "en-US"

    fun forLocale(locale: String): String {
        val candidates = linkedSetOf(locale, locale.substringBefore('-'), FALLBACK_LOCALE)

        for (candidate in candidates) {
            val text = javaClass.classLoader
                .getResourceAsStream("register-agreement/$candidate.html")
                ?.use { it.readBytes().toString(Charsets.UTF_8) }

            if (text != null) return text.trim()
        }

        return ""
    }
}
