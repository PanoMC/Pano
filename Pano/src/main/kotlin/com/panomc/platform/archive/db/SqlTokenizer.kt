package com.panomc.platform.archive.db

import com.panomc.platform.archive.PanoArcException
import com.panomc.platform.archive.PanoArcException.Code
import java.io.Reader

/** One lexical token of a SQL dump; [raw] is the exact source text (comments are never tokens). */
data class SqlToken(val type: Type, val raw: String) {
    enum class Type {
        /** Bare word: keyword or unquoted identifier. */
        WORD,

        /** Backtick-quoted identifier; [value] is the unescaped name. */
        IDENT,

        /** '...' or "..." string literal. */
        STRING,
        NUMBER,

        /** X'..' or 0x.. */
        HEX,

        /** B'..' or 0b.. */
        BIT,

        /** @var or @@var. */
        VARIABLE,

        /** A MySQL executable (version-conditional) comment, the kind that starts with a bang. */
        CONDITIONAL,

        /** Any other single character (parentheses, commas, operators, dots). */
        PUNCT
    }

    /** The identifier name for [Type.IDENT] / [Type.WORD]; the raw text otherwise. */
    val value: String
        get() = when (type) {
            Type.IDENT -> raw.substring(1, raw.length - 1).replace("``", "`")
            else -> raw
        }

    fun isWord(word: String) = type == Type.WORD && raw.equals(word, ignoreCase = true)

    fun isPunct(char: Char) = type == Type.PUNCT && raw.length == 1 && raw[0] == char
}

/**
 * A streaming MariaDB lexer that splits a dump into statements at top-level `;`, following the
 * server's default lexing rules (backslash escapes in strings, doubled quotes, dash-dash-space, hash and
 * block comments). Comments are dropped; executable comments become [SqlToken.Type.CONDITIONAL] tokens so
 * the caller can refuse them. Callers must keep the session sql_mode free of NO_BACKSLASH_ESCAPES
 * and ANSI_QUOTES, so the server lexes a statement exactly the way this class did.
 */
class SqlTokenizer(private val reader: Reader, private val maxStatementChars: Long = DEFAULT_MAX_STATEMENT_CHARS) {
    private var peeked = IntArray(3)
    private var peekedCount = 0
    private var statementChars = 0L

    private fun read(): Int {
        val c = if (peekedCount > 0) {
            val first = peeked[0]

            peeked[0] = peeked[1]
            peeked[1] = peeked[2]
            peekedCount--

            first
        } else {
            reader.read()
        }

        if (c >= 0 && ++statementChars > maxStatementChars) {
            throw PanoArcException(Code.UNSAFE_SQL, "A statement in the dump is longer than $maxStatementChars characters.")
        }

        return c
    }

    private fun peek(offset: Int = 0): Int {
        while (peekedCount <= offset) {
            peeked[peekedCount++] = reader.read()
        }

        return peeked[offset]
    }

    /** The next statement's tokens (empty list = an empty statement such as `;;`), or null at EOF. */
    fun nextStatement(): List<SqlToken>? {
        statementChars = 0
        val tokens = mutableListOf<SqlToken>()

        while (true) {
            val c = read()

            if (c < 0) {
                return if (tokens.isEmpty()) null else tokens
            }

            val ch = c.toChar()

            when {
                ch == ';' -> return tokens
                ch.isWhitespace() -> {}
                ch == '#' -> skipLine()
                ch == '-' && peek() == '-'.code && (peek(1) < 0 || peek(1).toChar().isWhitespace() || peek(1) < 32) -> skipLine()
                ch == '/' && peek() == '*'.code -> {
                    read()

                    val conditional = peek() == '!'.code || (peek() == 'M'.code && peek(1) == '!'.code)
                    val body = readBlockComment()

                    if (conditional) {
                        tokens.add(SqlToken(SqlToken.Type.CONDITIONAL, "/*$body*/"))
                    }
                }

                ch == '\'' || ch == '"' -> tokens.add(SqlToken(SqlToken.Type.STRING, readQuoted(ch)))
                ch == '`' -> tokens.add(SqlToken(SqlToken.Type.IDENT, readBacktick()))
                (ch == 'x' || ch == 'X') && peek() == '\''.code -> {
                    read()
                    tokens.add(SqlToken(SqlToken.Type.HEX, "X'" + readSimpleQuoted("0123456789abcdefABCDEF") + "'"))
                }

                (ch == 'b' || ch == 'B') && peek() == '\''.code -> {
                    read()
                    tokens.add(SqlToken(SqlToken.Type.BIT, "B'" + readSimpleQuoted("01") + "'"))
                }

                ch == '@' -> tokens.add(SqlToken(SqlToken.Type.VARIABLE, readVariable()))
                ch.isDigit() || (ch == '.' && peek() >= 0 && peek().toChar().isDigit()) -> tokens.add(readNumberOrWord(ch))
                isWordChar(ch) -> tokens.add(SqlToken(SqlToken.Type.WORD, readWord(ch)))
                else -> tokens.add(SqlToken(SqlToken.Type.PUNCT, ch.toString()))
            }
        }
    }

    private fun isWordChar(ch: Char) = ch.isLetterOrDigit() || ch == '_' || ch == '$' || ch.code >= 0x80

    private fun skipLine() {
        while (true) {
            val c = read()

            if (c < 0 || c == '\n'.code) {
                return
            }
        }
    }

    private fun readBlockComment(): String {
        val body = StringBuilder()

        while (true) {
            val c = read()

            if (c < 0) {
                throw PanoArcException(Code.UNSAFE_SQL, "Unterminated comment in the dump.")
            }

            if (c == '*'.code && peek() == '/'.code) {
                read()

                return body.toString()
            }

            body.append(c.toChar())
        }
    }

    private fun readQuoted(quote: Char): String {
        val raw = StringBuilder().append(quote)

        while (true) {
            val c = read()

            if (c < 0) {
                throw PanoArcException(Code.UNSAFE_SQL, "Unterminated string in the dump.")
            }

            val ch = c.toChar()

            raw.append(ch)

            if (ch == '\\') {
                val next = read()

                if (next < 0) {
                    throw PanoArcException(Code.UNSAFE_SQL, "Unterminated string in the dump.")
                }

                raw.append(next.toChar())
            } else if (ch == quote) {
                if (peek() == quote.code) {
                    raw.append(read().toChar())
                } else {
                    return raw.toString()
                }
            }
        }
    }

    private fun readBacktick(): String {
        val raw = StringBuilder().append('`')

        while (true) {
            val c = read()

            if (c < 0) {
                throw PanoArcException(Code.UNSAFE_SQL, "Unterminated identifier in the dump.")
            }

            raw.append(c.toChar())

            if (c == '`'.code) {
                if (peek() == '`'.code) {
                    raw.append(read().toChar())
                } else {
                    return raw.toString()
                }
            }
        }
    }

    private fun readSimpleQuoted(allowed: String): String {
        val body = StringBuilder()

        while (true) {
            val c = read()

            if (c < 0) {
                throw PanoArcException(Code.UNSAFE_SQL, "Unterminated literal in the dump.")
            }

            if (c == '\''.code) {
                return body.toString()
            }

            if (allowed.indexOf(c.toChar()) < 0) {
                throw PanoArcException(Code.UNSAFE_SQL, "Invalid character in a hex or bit literal.")
            }

            body.append(c.toChar())
        }
    }

    private fun readVariable(): String {
        val raw = StringBuilder("@")

        if (peek() == '@'.code) {
            raw.append(read().toChar())
        }

        while (peek() >= 0 && (isWordChar(peek().toChar()) || peek() == '.'.code)) {
            raw.append(read().toChar())
        }

        return raw.toString()
    }

    private fun readWord(first: Char): String {
        val raw = StringBuilder().append(first)

        while (peek() >= 0 && isWordChar(peek().toChar())) {
            raw.append(read().toChar())
        }

        return raw.toString()
    }

    private fun readNumberOrWord(first: Char): SqlToken {
        val raw = StringBuilder().append(first)

        while (true) {
            val next = peek()

            if (next < 0) {
                break
            }

            val ch = next.toChar()

            when {
                isWordChar(ch) || ch == '.' -> raw.append(read().toChar())
                // exponent sign: 1e+5 / 1.5E-3
                (ch == '+' || ch == '-') && raw.last().let { it == 'e' || it == 'E' } &&
                        NUMBER_PREFIX.matches(raw.dropLast(1)) && peek(1) >= 0 && peek(1).toChar().isDigit() ->
                    raw.append(read().toChar())

                else -> break
            }
        }

        val text = raw.toString()

        return when {
            NUMBER.matches(text) -> SqlToken(SqlToken.Type.NUMBER, text)
            HEX_NUMBER.matches(text) -> SqlToken(SqlToken.Type.HEX, text)
            BIT_NUMBER.matches(text) -> SqlToken(SqlToken.Type.BIT, text)
            text.contains('.') -> throw PanoArcException(Code.UNSAFE_SQL, "Invalid number $text in the dump.")
            else -> SqlToken(SqlToken.Type.WORD, text)
        }
    }

    companion object {
        const val DEFAULT_MAX_STATEMENT_CHARS = 64L * 1024 * 1024

        private val NUMBER = Regex("^(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")
        private val NUMBER_PREFIX = Regex("^(\\d+\\.?\\d*|\\.\\d+)$")
        private val HEX_NUMBER = Regex("^0x[0-9a-fA-F]+$")
        private val BIT_NUMBER = Regex("^0b[01]+$")
    }
}
