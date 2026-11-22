package com.panomc.platform.db

import io.vertx.core.Vertx
import io.vertx.mysqlclient.MySQLConnection
import io.vertx.mysqlclient.SslMode
import io.vertx.sqlclient.SqlConnection
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.concurrent.TimeUnit

/**
 * A connection must cost the server one slot, not two.
 *
 * Against a MariaDB that offers TLS with a self-signed certificate (the default from 11.4), Vert.x's
 * certificate-checking `PREFERRED` left a failed TLS socket open next to every connection, and a busy
 * pool ran into "Too many connections" (koyluler.tr, 2026-11-20/22). Gated: `PANO_IT_MARIADB=host:port`
 * (+ `PANO_IT_MARIADB_PASSWORD`, root).
 */
@EnabledIfEnvironmentVariable(named = "PANO_IT_MARIADB", matches = ".+")
class DatabaseConnectOptionsIT {
    private val vertx = Vertx.vertx()
    private val address = System.getenv("PANO_IT_MARIADB")
    private val password = System.getenv("PANO_IT_MARIADB_PASSWORD") ?: ""

    @AfterEach
    fun close() {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
    }

    private fun connect(sslMode: SslMode? = null): SqlConnection {
        val options = DatabaseConnectOptions.create()
            .setHost(address.substringBefore(':'))
            .setPort(address.substringAfter(':').toInt())
            .setUser("root")
            .setPassword(password)

        if (sslMode != null) options.sslMode = sslMode

        return MySQLConnection.connect(vertx, options).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
    }

    private fun SqlConnection.value(sql: String): Any? =
        query(sql).execute().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS).first().getValue(0)

    @Test
    fun `a connection leaves no half-open socket behind`() {
        val probe = connect(SslMode.DISABLED)
        val unauthenticated = "select count(*) from information_schema.processlist where user = 'unauthenticated user'"
        val before = probe.value(unauthenticated)

        val connection = connect()

        assertEquals(before, probe.value(unauthenticated))

        // When the server offers TLS the connection uses it instead of falling back to plain text.
        if (probe.value("select @@have_ssl") == "YES") {
            val cipher = connection.query("show session status like 'Ssl_cipher'").execute()
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS).first().getString(1)

            assert(cipher.isNotEmpty()) { "the connection is not encrypted" }
        }
    }
}
