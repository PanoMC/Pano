package com.panomc.platform.hosted

import com.panomc.platform.config.PanoConfig

/**
 * Environment → `config.conf` for container installs (workload-model.md "Env").
 *
 * On Pano Host (`PANO_HOSTED` set) the env is the source of truth for the database, mail relay, HTTP
 * port and trusted proxies and is re-applied on every boot: rotating the internal DB password
 * recreates the container with new env. Elsewhere (self-run image, bare metal with the vars set) it
 * only seeds a config that did not exist yet, so later panel edits win.
 *
 * `PANO_HOST_WORKLOAD_ID`, `PANO_HOST_INSTANCE_SECRET` and `PANO_HOST_API_URL` are read from env by
 * their consumers and never persisted.
 */
class HostedEnvConfig(private val env: Map<String, String> = System.getenv()) {
    companion object {
        /** Port the agent's Traefik routes to; port 80 cannot be bound under `--cap-drop ALL`. */
        const val CONTAINER_HTTP_PORT = 8088

        const val DEFAULT_DB_PORT = 3306
        const val DEFAULT_SMTP_PORT = 587

        /** Traefik reaches the workload over its private `pw-<id>` bridge network. */
        val PRIVATE_PROXY_RANGES = listOf(
            "127.0.0.1",
            "::1",
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "fc00::/7"
        )

        val current by lazy { HostedEnvConfig() }
    }

    class Database(val host: String, val port: Int, val name: String, val user: String, val password: String) {
        val hostWithPort get() = "$host:$port"

        override fun toString() = "Database(host=$host, port=$port, name=$name, user=$user, password=***)"
    }

    class Smtp(val host: String, val port: Int, val user: String, val password: String, val from: String?) {
        override fun toString() = "Smtp(host=$host, port=$port, user=$user, password=***, from=$from)"
    }

    private fun value(key: String): String? = env[key]?.trim()?.takeIf { it.isNotEmpty() }

    private fun port(key: String, default: Int?): Int? {
        val raw = value(key) ?: return default
        return raw.toIntOrNull()?.takeIf { it in 1..65535 } ?: default
    }

    val isHosted: Boolean = value("PANO_HOSTED") != null

    val isContainer: Boolean = isHosted || value("PANO_CONTAINER") == "1"

    val workloadId: String? = value("PANO_HOST_WORKLOAD_ID")

    val instanceSecret: String? = value("PANO_HOST_INSTANCE_SECRET")

    val hostApiUrl: String? = value("PANO_HOST_API_URL")?.trimEnd('/')

    /** Present when host, name and user are all set; the password may be empty. */
    val database: Database? = run {
        val host = value("PANO_DB_HOST") ?: return@run null
        val name = value("PANO_DB_NAME") ?: return@run null
        val user = value("PANO_DB_USER") ?: return@run null

        Database(host, port("PANO_DB_PORT", DEFAULT_DB_PORT)!!, name, user, env["PANO_DB_PASSWORD"] ?: "")
    }

    val smtp: Smtp? = run {
        val host = value("PANO_SMTP_HOST") ?: return@run null

        Smtp(
            host,
            port("PANO_SMTP_PORT", DEFAULT_SMTP_PORT)!!,
            value("PANO_SMTP_USER") ?: "",
            env["PANO_SMTP_PASSWORD"] ?: "",
            value("PANO_SMTP_FROM")
        )
    }

    /** `PANO_HTTP_PORT`, else 8088 in container mode, else none. */
    val httpPort: Int? = port("PANO_HTTP_PORT", null) ?: if (isContainer) CONTAINER_HTTP_PORT else null

    /** The DB step of the setup wizard is owned by the environment. */
    val databaseManaged get() = database != null

    val hasAny get() = database != null || smtp != null || httpPort != null || isHosted

    /** Hosted: every boot. Otherwise only when the config file is being created. */
    fun shouldApply(creatingConfig: Boolean) = hasAny && (isHosted || creatingConfig)

    /**
     * Writes the env values into [config] and returns the names of the keys that changed (never
     * their values, which include secrets).
     */
    fun apply(config: PanoConfig): List<String> {
        val changed = mutableListOf<String>()

        fun <T> set(key: String, current: T, new: T, write: (T) -> Unit) {
            if (current != new) {
                write(new)
                changed += key
            }
        }

        database?.let { db ->
            val c = config.database
            set("database.type", c.type, "mariadb") { c.type = it }
            set("database.host", c.host, db.hostWithPort) { c.host = it }
            set("database.name", c.name, db.name) { c.name = it }
            set("database.username", c.username, db.user) { c.username = it }
            set("database.password", c.password, db.password) { c.password = it }
        }

        smtp?.let { smtp ->
            val c = config.email
            val (ssl, starttls) = when (smtp.port) {
                465 -> true to "DISABLED"
                587 -> false to "REQUIRED"
                else -> false to "OPTIONAL"
            }

            set("email.enabled", c.enabled, true) { c.enabled = it }
            set("email.hostname", c.hostname, smtp.host) { c.hostname = it }
            set("email.port", c.port, smtp.port) { c.port = it }
            set("email.username", c.username, smtp.user) { c.username = it }
            set("email.password", c.password, smtp.password) { c.password = it }
            set("email.ssl", c.ssl, ssl) { c.ssl = it }
            set("email.starttls", c.starttls, starttls) { c.starttls = it }
            smtp.from?.let { from -> set("email.sender", c.sender, from) { c.sender = it } }
        }

        httpPort?.let { port -> set("server.http-port", config.server.httpPort, port) { config.server.httpPort = it } }

        if (isHosted) {
            val proxies = config.server.trustedProxies
            val merged = proxies + PRIVATE_PROXY_RANGES.filter { it !in proxies }
            set("server.trusted-proxies", proxies, merged) { config.server.trustedProxies = it }
        }

        return changed
    }
}
