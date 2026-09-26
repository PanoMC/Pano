package com.panomc.platform.archive.db

/** SQL literal and identifier rendering for the Pano-native dumper (mariadb-dump compatible escapes). */
object SqlLiterals {
    private val NUMBER = Regex("^-?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")
    private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()

    /** `name` quoted with backticks, embedded backticks doubled. */
    fun identifier(name: String): String = "`" + name.replace("`", "``") + "`"

    /** A single-quoted string literal with `\0 \n \r \Z \\ \' \"` escaped, or NULL. */
    fun string(value: String?): String {
        if (value == null) {
            return "NULL"
        }

        val out = StringBuilder(value.length + 2).append('\'')

        value.forEach { ch ->
            when (ch) {
                '\u0000' -> out.append("\\0")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\u001A' -> out.append("\\Z")
                '\\' -> out.append("\\\\")
                '\'' -> out.append("\\'")
                '"' -> out.append("\\\"")
                else -> out.append(ch)
            }
        }

        return out.append('\'').toString()
    }

    /** `X'…'` for bytes (`X''` for empty), or NULL. */
    fun binary(value: ByteArray?): String {
        if (value == null) {
            return "NULL"
        }

        val out = StringBuilder(value.size * 2 + 3).append("X'")

        value.forEach {
            val b = it.toInt() and 0xFF

            out.append(HEX_DIGITS[b ushr 4]).append(HEX_DIGITS[b and 0x0F])
        }

        return out.append('\'').toString()
    }

    /** A hex string the server produced with HEX(), validated and wrapped as `X'…'`. */
    fun hex(value: String?): String {
        if (value == null) {
            return "NULL"
        }

        require(value.length % 2 == 0 && value.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) { "Not a hex string." }

        return "X'$value'"
    }

    /** A number as-is when it is a plain decimal/float literal, otherwise quoted (never injected raw). */
    fun number(value: String?): String = when {
        value == null -> "NULL"
        NUMBER.matches(value) -> value
        else -> string(value)
    }
}
