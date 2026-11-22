package com.panomc.platform.db

import io.vertx.core.net.ClientSSLOptions
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.mysqlclient.SslMode

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
}
