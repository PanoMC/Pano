package com.panomc.platform.util

object Regexes {
    const val USERNAME = "^[a-zA-Z0-9_]+\$"

    // Local part: RFC 5322 atext and dots (so plus-addressing works), at most 64 characters. Domain: labels of
    // letters, digits, '-' and '_', any TLD of 2 to 63 characters. 254 characters in total. No spaces, quotes,
    // commas, brackets or control characters, so an address can never break a mail header.
    const val EMAIL =
        "^(?=.{3,254}\$)[A-Za-z0-9!#\$%&'*+/=?^_`{|}~.-]{1,64}@([A-Za-z0-9_-]+\\.)+[A-Za-z0-9_-]{2,63}\$"
}
