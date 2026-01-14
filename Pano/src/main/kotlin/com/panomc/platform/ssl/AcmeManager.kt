package com.panomc.platform.ssl

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.ext.web.Router
import org.shredzone.acme4j.AccountBuilder
import org.shredzone.acme4j.Session
import org.shredzone.acme4j.Status
import org.shredzone.acme4j.challenge.Http01Challenge
import org.shredzone.acme4j.util.CSRBuilder
import org.shredzone.acme4j.util.KeyPairUtils
import org.slf4j.Logger
import org.springframework.stereotype.Component
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.net.URI
import java.security.KeyPair
import java.util.concurrent.atomic.AtomicReference

@Component
class AcmeManager(
    private val vertx: Vertx,
    private val logger: Logger,
    private val configManager: ConfigManager
) {
    private val certDir = File("certificates")
    private val accountKeyFile = File(certDir, "account.key")
    private val domainKeyFile = File(certDir, "domain.key")
    private val certificateFile = File(certDir, "certificate.crt")
    private val chainFile = File(certDir, "chain.crt")
    private val fullChainFile = File(certDir, "fullchain.crt")

    // Let's Encrypt directory URL. For testing, you could use staging: "acme://letsencrypt.org/staging"
    private val acmeServerUrl = "acme://letsencrypt.org"

    private val currentOptions = AtomicReference<PemKeyCertOptions?>(null)

    fun init(router: Router) {
        if (!certDir.exists()) {
            certDir.mkdirs()
        }

        // Add route for ACME HTTP-01 challenge
        router.get("/.well-known/acme-challenge/:token").order(0).handler { ctx ->
            val token = ctx.pathParam("token")
            logger.info("Received ACME challenge request for token: $token")
            val challengeFile = File(certDir, "challenge-$token")
            if (challengeFile.exists()) {
                ctx.response().putHeader("Content-Type", "text/plain").end(challengeFile.readText())
            } else {
                logger.warn("ACME challenge token not found locally: $token")
                ctx.fail(404)
            }
        }

        loadExistingCertificates()
    }

    private fun loadExistingCertificates() {
        if (domainKeyFile.exists() && fullChainFile.exists()) {
            try {
                val certOptions = PemKeyCertOptions()
                    .setKeyValue(Buffer.buffer(domainKeyFile.readBytes()))
                    .setCertValue(Buffer.buffer(fullChainFile.readBytes()))
                currentOptions.set(certOptions)
                logger.info("Loaded existing Let's Encrypt certificates.")
            } catch (e: Exception) {
                logger.error("Failed to load existing Let's Encrypt certificates: ${e.message}")
            }
        }
    }

    fun getCertificateOptions(): PemKeyCertOptions? {
        return currentOptions.get()
    }

    fun prepareCertificates() {
        if (configManager.config.server.sslMode != PanoConfig.Companion.SslMode.LETS_ENCRYPT) return

        val websiteUrl = configManager.config.websiteUrl
        if (websiteUrl.isBlank()) {
            logger.error("Let's Encrypt is enabled but Website URL is not set in settings!")
            return
        }

        val domain = (URI(websiteUrl).host ?: websiteUrl.removePrefix("http://").removePrefix("https://").substringBefore("/")).substringBefore(":")
        if (domain.isBlank() || domain == "localhost" || domain == "127.0.0.1") {
            logger.error("Website URL '$websiteUrl' is not a valid public domain for Let's Encrypt.")
            return
        }

        // Run in a separate thread to avoid blocking Vert.x event loop
        Thread {
            try {
                if (shouldRenew()) {
                    logger.info("Starting Let's Encrypt certificate request for domain: $domain")
                    requestCertificate(domain)
                } else {
                    logger.info("Let's Encrypt certificate for $domain is still valid.")
                }
            } catch (e: Exception) {
                logger.error("Let's Encrypt process failed: ${e.message}")
                e.printStackTrace()
            }
        }.start()
    }

    private fun shouldRenew(): Boolean {
        if (!fullChainFile.exists() || !domainKeyFile.exists()) return true
        
        // Basic check for expiration could be added here.
        // For now, if files exist, we assume they are valid unless we manually trigger renewal.
        // A real implementation should parse the cert and check expiration date.
        return false 
    }

    private fun requestCertificate(domain: String) {
        val session = Session(URI(acmeServerUrl))

        // Load or create account key
        val accountKeyPair = loadOrCreateKeyPair(accountKeyFile)
        val login = AccountBuilder()
            .useKeyPair(accountKeyPair)
            .agreeToTermsOfService()
            .createLogin(session)
        val account = login.account

        // Create order
        val order = login.newOrder().domain(domain).create()

        // Handle challenges
        for (auth in order.authorizations) {
            if (auth.status == Status.VALID) continue

            val challenge = auth.findChallenge(Http01Challenge::class.java).orElse(null)
                ?: throw Exception("HTTP-01 challenge not found for $domain")
            
            // Save challenge token to be served by Vert.x
            val token = challenge.getToken()
            val content = challenge.getAuthorization()
            File(certDir, "challenge-$token").writeText(content)

            // Trigger challenge validation
            challenge.trigger()

            // Wait for validation
            var attempts = 15
            while (auth.status != Status.VALID && attempts-- > 0) {
                if (auth.status == Status.INVALID) {
                    val chalError = challenge.getError().orElse(null)
                    val reason = chalError?.detail ?: chalError?.toString() ?: "Unknown error"
                    throw Exception("ACME authorization failed for $domain. Reason: $reason")
                }
                logger.info("Waiting for domain verification... Status: ${auth.status} (Attempts left: $attempts)")
                Thread.sleep(3000)
                auth.update()
            }

            if (auth.status != Status.VALID) {
                throw Exception("ACME authorization timed out for $domain. Current status: ${auth.status}")
            }
            
            // Clean up challenge file
            File(certDir, "challenge-$token").delete()
        }

        // Finalize order
        val domainKeyPair = loadOrCreateKeyPair(domainKeyFile)
        val csrb = CSRBuilder()
        csrb.addDomain(domain)
        csrb.sign(domainKeyPair)
        val csr = csrb.encoded
        order.execute(csr)

        // Wait for certificate
        var attempts = 10
        while (order.status != Status.VALID && attempts-- > 0) {
            Thread.sleep(3000)
            order.update()
        }

        val certificate = order.certificate ?: throw Exception("Certificate not issued")
        
        // Save certificates
        FileWriter(fullChainFile).use { certificate.writeCertificate(it) }
        
        logger.info("Successfully obtained and saved Let's Encrypt certificates for $domain.")

        // Update current options and notify Pano to reload if necessary
        loadExistingCertificates()
        
        // In a real scenario, you might want to call a "reload" method on the Vert.x server options.
        // For now, it will be used on next restart.
    }

    private fun loadOrCreateKeyPair(file: File): KeyPair {
        return if (file.exists()) {
            FileReader(file).use { KeyPairUtils.readKeyPair(it) }
        } else {
            val keyPair = KeyPairUtils.createKeyPair(2048)
            FileWriter(file).use { KeyPairUtils.writeKeyPair(keyPair, it) }
            keyPair
        }
    }
}
