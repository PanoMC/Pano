package com.panomc.platform.token

import java.util.*

enum class TokenType(val expireDate: (() -> Long)) {
    AUTHENTICATION({
        val calendar = Calendar.getInstance()

        calendar.add(Calendar.MONTH, 1)

        calendar.timeInMillis
    }),
    ACTIVATION({
        val calendar = Calendar.getInstance()

        calendar.add(Calendar.MINUTE, 15)

        calendar.timeInMillis
    }),
    RESET_PASSWORD({
        val calendar = Calendar.getInstance()

        calendar.add(Calendar.MINUTE, 30)

        calendar.timeInMillis
    }),
    SERVER_AUTHENTICATION({
        val calendar = Calendar.getInstance()

        calendar.add(Calendar.YEAR, 10)

        calendar.timeInMillis
    })
}