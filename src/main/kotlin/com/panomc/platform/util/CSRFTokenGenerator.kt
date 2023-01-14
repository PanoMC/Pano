package com.panomc.platform.util

import java.math.BigInteger
import java.security.SecureRandom

object CSRFTokenGenerator {
    fun nextToken(): String {
        val random = SecureRandom()

        return BigInteger(130, random).toString(32)
    }
}
