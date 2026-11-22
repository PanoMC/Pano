package com.panomc.platform.db

import io.vertx.core.net.ClientSSLOptions
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.mysqlclient.SslMode
import io.vertx.sqlclient.PoolOptions
import java.util.concurrent.TimeUnit

/**
 * Connect options every MySQL / MariaDB connection Pano opens starts from.
 *
 * TLS is used when the server offers it, without checking the certificate, which is what `PREFERRED`
 * means for the MySQL clients. With Vert.x's own `PREFERRED` the certificate is checked: MariaDB
 * 11.4+ offers TLS with a self-signed one by default, so the TLS handshake failed, the client
 * connected again without TLS and left the first socket open. MariaDB counts that socket for
 * `connect_timeout` (10 s), so a burst of new connections used twice the pool size and a pool of
 * 100 hit the default `max_connections` of 151 ("Too many connections", which the client then
 * fails to parse: `IndexOutOfBoundsException ... range(0, 27)` on the event loop).
 */
object DatabaseConnectOptions {
    fun create(): MySQLConnectOptions = MySQLConnectOptions()
        .setSslMode(SslMode.PREFERRED)
        .setSslOptions(ClientSSLOptions().setTrustAll(true).setHostnameVerificationAlgorithm(""))

    /** Seconds a pooled connection may sit unused before it is closed. */
    const val IDLE_TIMEOUT_SECONDS = 60

    /**
     * Options of Pano's shared pool. A connection unused for [idleTimeoutSeconds] is closed: by default
     * Vert.x keeps every connection a busy moment opened until the server drops it (`wait_timeout`, 8 h),
     * so an idle Pano held its whole pool. On a database shared with other Panos (Pano Host) or with
     * the Minecraft server's plugins, those slots are then missing for everyone else.
     */
    fun pool(maxSize: Int, idleTimeoutSeconds: Int = IDLE_TIMEOUT_SECONDS): PoolOptions = PoolOptions()
        .setMaxSize(maxSize)
        .setIdleTimeout(idleTimeoutSeconds)
        .setIdleTimeoutUnit(TimeUnit.SECONDS)
}
