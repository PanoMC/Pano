package com.panomc.platform.webhook


import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.net.PemTrustOptions
import io.vertx.core.net.PfxOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The [UNPROVEN] item of 11 section 7.2 step 10: the connection goes to the validated IP address while SNI and the
 * certificate check use the host name. A server certificate that is valid only for `pinned.test` is reached through a
 * resolver that maps that name to 127.0.0.1: the handshake can only succeed when the client verified the certificate
 * against the host name (the address 127.0.0.1 is not in the certificate), and the server records the SNI it was sent.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboundHttpTlsTest {
    private lateinit var vertx: Vertx

    @BeforeAll
    fun start() {
        vertx = Vertx.vertx()
    }

    @AfterAll
    fun stop() {
        vertx.close().toCompletionStage().toCompletableFuture().get()
    }

    private class Tls(val keystore: Path, val certPem: Path)

    private fun certificate(dir: Path, name: String, san: String): Tls {
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val ks = dir.resolve("$name.p12")
        val pem = dir.resolve("$name.pem")
        fun run(vararg args: String) {
            val p = ProcessBuilder(listOf(keytool) + args).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { "keytool failed: $out" }
        }
        run(
            "-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=$san",
            "-ext", "san=dns:$san", "-keystore", ks.toString(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit"
        )
        run("-exportcert", "-rfc", "-alias", name, "-keystore", ks.toString(), "-storepass", "changeit", "-file", pem.toString())
        return Tls(ks, pem)
    }

    private class TlsServer(val server: HttpServer, val sni: CopyOnWriteArrayList<String?>, val hosts: CopyOnWriteArrayList<String?>) {
        val port: Int get() = server.actualPort()
        fun close() = server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
    }

    private fun server(tls: Tls): TlsServer {
        val sni = CopyOnWriteArrayList<String?>()
        val hosts = CopyOnWriteArrayList<String?>()
        val options = HttpServerOptions().setSsl(true).setSni(true)
            .setKeyCertOptions(PfxOptions().setPath(tls.keystore.toString()).setPassword("changeit"))
        val server = vertx.createHttpServer(options).requestHandler { req ->
            sni += req.connection().indicatedServerName()
            hosts += req.getHeader("Host")
            req.response().setStatusCode(200).end("secure")
        }
        server.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
        return TlsServer(server, sni, hosts)
    }

    private fun outbound(resolver: StubResolver, trust: Tls) = WebhookTestSupport.outbound(vertx, resolver, 5_000) { options ->
        options.setTrustOptions(PemTrustOptions().addCertPath(trust.certPem.toString()))
    }

    @Test
    fun `tls connects to the pinned address while hostname verification and SNI use the host name`(@TempDir dir: Path): Unit = runBlocking {
        val tls = certificate(dir, "pinned", "pinned.test")
        val server = server(tls)
        try {
            val http = outbound(StubResolver("pinned.test" to listOf("127.0.0.1")), tls)

            val attempt = http.post("https://pinned.test:${server.port}/hook", listOf("Content-Type" to "application/json"), "{}".toByteArray(), allowPrivate = true)

            assertNull(attempt.error)
            assertEquals(200, attempt.statusCode)
            assertEquals("secure", attempt.response)
            assertEquals(listOf<String?>("pinned.test"), server.sni.toList())
            assertEquals("pinned.test:${server.port}", server.hosts.single())
        } finally {
            server.close()
        }
    }

    @Test
    fun `a certificate for another host name is rejected although the address matches`(@TempDir dir: Path): Unit = runBlocking {
        val tls = certificate(dir, "other", "other.test")
        val server = server(tls)
        try {
            val http = outbound(StubResolver("pinned.test" to listOf("127.0.0.1")), tls)

            val attempt = http.post("https://pinned.test:${server.port}/hook", emptyList(), "{}".toByteArray(), allowPrivate = true)

            assertNull(attempt.statusCode)
            assertNotNull(attempt.error)
            assertTrue(attempt.error!!.startsWith("IO:"), attempt.error)
            assertTrue(server.hosts.isEmpty(), "no request may be sent over an unverified connection")
        } finally {
            server.close()
        }
    }

    @Test
    fun `an untrusted certificate is rejected`(@TempDir dir: Path): Unit = runBlocking {
        val tls = certificate(dir, "pinned2", "pinned.test")
        val unrelated = certificate(dir, "unrelated", "unrelated.test")
        val server = server(tls)
        try {
            val http = outbound(StubResolver("pinned.test" to listOf("127.0.0.1")), unrelated)

            val attempt = http.post("https://pinned.test:${server.port}/hook", emptyList(), "{}".toByteArray(), allowPrivate = true)

            assertNull(attempt.statusCode)
            assertTrue(attempt.error!!.startsWith("IO:"), attempt.error)
            assertTrue(server.hosts.isEmpty())
        } finally {
            server.close()
        }
    }
}
