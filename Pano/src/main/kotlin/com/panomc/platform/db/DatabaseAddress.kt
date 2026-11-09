package com.panomc.platform.db

/**
 * The `database.host` setting split into host and port: `host`, `host:port`, an IPv6 literal (`::1`) or a
 * bracketed one with a port (`[::1]:3306`). The port defaults to [DEFAULT_PORT].
 */
data class DatabaseAddress(val host: String, val port: Int) {
    companion object {
        const val DEFAULT_PORT = 3306

        /** Null when [value] has a port that is not a number from 1 to 65535, or an unclosed bracket. */
        fun parse(value: String): DatabaseAddress? {
            val address = value.trim()

            if (address.startsWith("[")) {
                val end = address.indexOf(']')

                if (end < 0) {
                    return null
                }

                val host = address.substring(1, end)
                val rest = address.substring(end + 1)

                return when {
                    rest.isEmpty() -> DatabaseAddress(host, DEFAULT_PORT)
                    rest.startsWith(":") -> port(rest.substring(1))?.let { DatabaseAddress(host, it) }
                    else -> null
                }
            }

            // More than one colon and no brackets: a bare IPv6 literal, which cannot carry a port.
            if (address.count { it == ':' } > 1) {
                return DatabaseAddress(address, DEFAULT_PORT)
            }

            if (!address.contains(':')) {
                return DatabaseAddress(address, DEFAULT_PORT)
            }

            return port(address.substringAfter(':'))?.let { DatabaseAddress(address.substringBefore(':'), it) }
        }

        private fun port(value: String): Int? = value.toIntOrNull()?.takeIf { it in 1..65535 }
    }
}
