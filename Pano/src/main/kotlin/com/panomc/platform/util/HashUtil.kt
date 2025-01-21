package com.panomc.platform.util

import org.apache.commons.io.IOUtils
import java.io.InputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PrivateKey
import javax.crypto.Cipher

object HashUtil {
    fun InputStream.hash() =
        String.format("%064x", BigInteger(1, MessageDigest.getInstance("SHA-256").digest(IOUtils.toByteArray(this))))

    @Throws(java.lang.Exception::class)
    fun decryptData(encryptedData: ByteArray, privateKey: PrivateKey): String {
        val cipher = Cipher.getInstance("RSA")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val decryptedBytes = cipher.doFinal(encryptedData)
        return String(decryptedBytes)
    }
}