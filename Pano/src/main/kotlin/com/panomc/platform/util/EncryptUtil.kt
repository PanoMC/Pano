package com.panomc.platform.util

import java.security.PrivateKey
import java.security.PublicKey
import javax.crypto.Cipher

object EncryptUtil {
    @Throws(java.lang.Exception::class)
    fun decryptData(encryptedData: ByteArray, privateKey: PrivateKey): String {
        val cipher = Cipher.getInstance("RSA")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val decryptedBytes = cipher.doFinal(encryptedData)
        return String(decryptedBytes)
    }

    @Throws(Exception::class)
    fun encryptData(data: String, publicKey: PublicKey): ByteArray {
        val cipher = Cipher.getInstance("RSA")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(data.toByteArray())
    }
}