package com.panomc.platform.util

import org.apache.commons.io.IOUtils
import java.io.InputStream
import java.math.BigInteger
import java.security.MessageDigest

object HashUtil {
    fun InputStream.hash() =
        String.format("%064x", BigInteger(1, MessageDigest.getInstance("SHA-256").digest(IOUtils.toByteArray(this))))
}