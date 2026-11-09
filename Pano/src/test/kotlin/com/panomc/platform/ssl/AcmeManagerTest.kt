package com.panomc.platform.ssl

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.util.Date

class AcmeManagerTest {
    private val day = 24L * 60 * 60 * 1000

    private fun pemValidUntil(notAfter: Long): ByteArray {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=example.com")
        val holder = JcaX509v3CertificateBuilder(
            name, BigInteger.ONE, Date(notAfter - 90 * day), Date(notAfter), name, keyPair.public
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        val certificate = JcaX509CertificateConverter().getCertificate(holder)

        return StringWriter().also { out -> JcaPEMWriter(out).use { it.writeObject(certificate) } }
            .toString().toByteArray()
    }

    @Test
    fun `only ACME tokens are looked up`() {
        assertTrue(AcmeManager.isValidToken("LoqXcYV8q5ONbJQxbmR7SCTNo3tiAXDfowyjxAjEuX0"))
        listOf(
            null,
            "",
            "..\\..\\config.conf",
            "../../config.conf",
            "a.b",
            "token\nINFO forged",
            "x".repeat(129)
        ).forEach { assertFalse(AcmeManager.isValidToken(it), it.toString()) }
    }

    @Test
    fun `a certificate is renewed 30 days before it expires`() {
        val now = System.currentTimeMillis()

        assertFalse(AcmeManager.needsRenewal(pemValidUntil(now + 60 * day), now))
        assertTrue(AcmeManager.needsRenewal(pemValidUntil(now + 10 * day), now))
        assertTrue(AcmeManager.needsRenewal(pemValidUntil(now - day), now))
        assertTrue(AcmeManager.needsRenewal("not a certificate".toByteArray(), now))
    }
}
