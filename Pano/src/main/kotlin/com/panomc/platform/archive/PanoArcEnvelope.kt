package com.panomc.platform.archive

import com.panomc.platform.archive.PanoArcException.Code
import io.vertx.core.json.JsonObject
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** How an envelope's master key is obtained. `none` archives have no envelope at all (plain zip). */
enum class PanoArcKeyMode(val value: String) {
    WORKLOAD("workload"),
    PASSPHRASE("passphrase");

    companion object {
        fun from(value: String?) = values().firstOrNull { it.value == value }
    }
}

/** What to encrypt a new archive with. */
sealed class PanoArcEncryption {
    /** A random 256-bit per-workload key; [keyId] is the key version recorded in the header. */
    class Workload(val key: ByteArray, val keyId: String) : PanoArcEncryption() {
        init {
            require(key.size == PanoArcCrypto.KEY_BYTES) { "A workload key is 32 bytes." }
        }
    }

    /** End-to-end: Argon2id(passphrase) with fresh salt; the passphrase never leaves this server. */
    class Passphrase(
        val passphrase: CharArray,
        val t: Int = PanoArcCrypto.ARGON2_T,
        val mKiB: Int = PanoArcCrypto.ARGON2_M_KIB,
        val p: Int = PanoArcCrypto.ARGON2_P
    ) : PanoArcEncryption()
}

/** What may open an archive. Only the part matching the header's keyMode is used. */
class PanoArcKeys(
    val passphrase: CharArray? = null,
    val workloadKey: ((keyId: String?) -> ByteArray?)? = null
) {
    companion object {
        val NONE = PanoArcKeys()
    }
}

data class PanoArcKdf(val alg: String, val t: Int, val mKiB: Int, val p: Int, val salt: ByteArray)

/** The parsed envelope header; [raw] are the exact header bytes, which are part of every chunk's AAD. */
class PanoArcHeader(
    val raw: ByteArray,
    val chunkSize: Int,
    val keyMode: PanoArcKeyMode,
    val keyId: String?,
    val kdf: PanoArcKdf?,
    val noncePrefix: ByteArray,
    val check: ByteArray
)

/**
 * The `.panoarc` encryption envelope (archive-format.md section 3).
 *
 * ```
 * magic "PANOARC\x01" | u32 headerLen | header JSON | chunk*
 * chunk = u32 ctLen | ciphertext||tag
 * ```
 *
 * Every chunk but the last carries exactly `chunkSize` plaintext bytes; the last one carries
 * 0..chunkSize and is the only one sealed with lastFlag = 1. Nonce = noncePrefix || u32 counter,
 * AAD = header || u32 counter || lastFlag, so a modified header, a reordered, dropped or cut chunk
 * and a stream that simply stops at a chunk boundary all fail authentication. All integers are
 * big-endian.
 */
object PanoArcEnvelope {
    val MAGIC = byteArrayOf('P'.code.toByte(), 'A'.code.toByte(), 'N'.code.toByte(), 'O'.code.toByte(),
        'A'.code.toByte(), 'R'.code.toByte(), 'C'.code.toByte(), 0x01)

    const val ALG = "AES-256-GCM-STREAM"
    const val CHUNK_SIZE = 1024 * 1024
    const val TAG_BYTES = 16
    const val MAX_HEADER_BYTES = 16 * 1024
    const val MIN_CHUNK_SIZE = 1024
    const val MAX_CHUNK_SIZE = 16 * 1024 * 1024

    /** Upper bounds for a KDF read from an (untrusted) header, so a header cannot demand 1 TB of RAM. */
    private const val MAX_ARGON2_T = 10
    private const val MIN_ARGON2_M_KIB = 8 * 1024
    private const val MAX_ARGON2_M_KIB = 1024 * 1024
    private const val MAX_ARGON2_P = 8

    private const val MAX_COUNTER = 0xFFFFFFFFL

    /**
     * Wraps [output] so everything written to the result is encrypted. Closing the result writes
     * the final chunk and closes [output].
     */
    fun encrypt(output: OutputStream, encryption: PanoArcEncryption): OutputStream =
        encrypt(output, encryption, CHUNK_SIZE)

    internal fun encrypt(
        output: OutputStream,
        encryption: PanoArcEncryption,
        chunkSize: Int,
        sealLast: Boolean = true
    ): EncryptingOutputStream {
        require(chunkSize in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) { "Invalid chunk size." }

        val noncePrefix = PanoArcCrypto.randomBytes(PanoArcCrypto.NONCE_PREFIX_BYTES)
        val header = JsonObject()
            .put("alg", ALG)
            .put("chunkSize", chunkSize)

        val masterKey = when (encryption) {
            is PanoArcEncryption.Workload -> {
                header.put("keyMode", PanoArcKeyMode.WORKLOAD.value).put("keyId", encryption.keyId)

                encryption.key.copyOf()
            }

            is PanoArcEncryption.Passphrase -> {
                val salt = PanoArcCrypto.randomBytes(PanoArcCrypto.SALT_BYTES)

                header.put("keyMode", PanoArcKeyMode.PASSPHRASE.value).put(
                    "kdf", JsonObject()
                        .put("alg", "argon2id")
                        .put("t", encryption.t)
                        .put("mKiB", encryption.mKiB)
                        .put("p", encryption.p)
                        .put("salt", b64(salt))
                )

                PanoArcCrypto.argon2id(encryption.passphrase, salt, encryption.t, encryption.mKiB, encryption.p)
            }
        }

        header.put("noncePrefix", b64(noncePrefix)).put("check", b64(PanoArcCrypto.checkValue(masterKey)))

        val encKey = PanoArcCrypto.encKey(masterKey)

        masterKey.fill(0)

        val headerBytes = header.encode().toByteArray(StandardCharsets.UTF_8)

        output.write(MAGIC)
        output.write(u32(headerBytes.size.toLong()))
        output.write(headerBytes)

        return EncryptingOutputStream(output, headerBytes, noncePrefix, encKey, chunkSize, sealLast)
    }

    /** Whether [prefix] starts with the envelope magic. */
    fun isEnvelope(prefix: ByteArray) = prefix.size >= MAGIC.size && prefix.copyOf(MAGIC.size).contentEquals(MAGIC)

    /**
     * Opens an archive stream: a `.panoarc` envelope is decrypted with [keys] (the key check runs
     * here, before any chunk), a plain zip (keyMode none) is passed through. Anything else is
     * [Code.NOT_AN_ARCHIVE].
     */
    fun open(input: InputStream, keys: PanoArcKeys): InputStream {
        val pushback = PushbackInputStream(input, MAGIC.size)
        val prefix = readUpTo(pushback, MAGIC.size)

        if (isEnvelope(prefix)) {
            return decrypt(pushback, parseHeaderAfterMagic(pushback), keys)
        }

        if (prefix.size >= 4 && prefix[0] == 'P'.code.toByte() && prefix[1] == 'K'.code.toByte() &&
            ((prefix[2].toInt() == 3 && prefix[3].toInt() == 4) || (prefix[2].toInt() == 5 && prefix[3].toInt() == 6))
        ) {
            pushback.unread(prefix)

            return pushback
        }

        throw PanoArcException(Code.NOT_AN_ARCHIVE)
    }

    /** Reads magic + header only, e.g. to ask for a passphrase before the real read. Null for a plain zip. */
    fun readHeader(input: InputStream): PanoArcHeader? {
        val prefix = readUpTo(input, MAGIC.size)

        if (!isEnvelope(prefix)) {
            return null
        }

        return parseHeaderAfterMagic(input)
    }

    /** Decrypts the chunks that follow an already parsed [header]. */
    fun decrypt(input: InputStream, header: PanoArcHeader, keys: PanoArcKeys): InputStream {
        val masterKey = when (header.keyMode) {
            PanoArcKeyMode.PASSPHRASE -> {
                val passphrase = keys.passphrase ?: throw PanoArcException(Code.PASSPHRASE_REQUIRED)
                val kdf = header.kdf!!

                PanoArcCrypto.argon2id(passphrase, kdf.salt, kdf.t, kdf.mKiB, kdf.p)
            }

            PanoArcKeyMode.WORKLOAD -> {
                val key = keys.workloadKey?.invoke(header.keyId) ?: throw PanoArcException(Code.KEY_REQUIRED)

                if (key.size != PanoArcCrypto.KEY_BYTES) {
                    throw PanoArcException(Code.WRONG_KEY)
                }

                key.copyOf()
            }
        }

        try {
            if (!PanoArcCrypto.checkMatches(masterKey, header.check)) {
                throw PanoArcException(
                    if (header.keyMode == PanoArcKeyMode.PASSPHRASE) Code.WRONG_PASSPHRASE else Code.WRONG_KEY
                )
            }

            return DecryptingInputStream(input, header, PanoArcCrypto.encKey(masterKey))
        } finally {
            masterKey.fill(0)
        }
    }

    private fun parseHeaderAfterMagic(input: InputStream): PanoArcHeader {
        val lengthBytes = readUpTo(input, 4)

        if (lengthBytes.size < 4) {
            throw PanoArcException(Code.TRUNCATED, "The archive ends inside its header.")
        }

        val length = readU32(lengthBytes, 0)

        if (length < 2 || length > MAX_HEADER_BYTES) {
            throw PanoArcException(Code.UNSUPPORTED_FORMAT, "Invalid header length.")
        }

        val raw = readUpTo(input, length.toInt())

        if (raw.size < length) {
            throw PanoArcException(Code.TRUNCATED, "The archive ends inside its header.")
        }

        return parseHeader(raw)
    }

    internal fun parseHeader(raw: ByteArray): PanoArcHeader {
        try {
            val json = JsonObject(String(raw, StandardCharsets.UTF_8))

            if (json.getString("alg") != ALG) {
                throw PanoArcException(Code.UNSUPPORTED_FORMAT, "Unsupported algorithm.")
            }

            val chunkSize = json.getInteger("chunkSize") ?: -1

            if (chunkSize !in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) {
                throw PanoArcException(Code.UNSUPPORTED_FORMAT, "Unsupported chunk size.")
            }

            val keyMode = PanoArcKeyMode.from(json.getString("keyMode"))
                ?: throw PanoArcException(Code.UNSUPPORTED_FORMAT, "Unsupported key mode.")

            val noncePrefix = unb64(json.getString("noncePrefix"))
            val check = unb64(json.getString("check"))

            if (noncePrefix.size != PanoArcCrypto.NONCE_PREFIX_BYTES || check.size != 32) {
                throw PanoArcException(Code.UNSUPPORTED_FORMAT, "Invalid nonce prefix or check value.")
            }

            val kdf = if (keyMode == PanoArcKeyMode.PASSPHRASE) {
                val kdfJson = json.getJsonObject("kdf")
                    ?: throw PanoArcException(Code.UNSUPPORTED_FORMAT, "Missing KDF parameters.")

                val kdf = PanoArcKdf(
                    kdfJson.getString("alg") ?: "",
                    kdfJson.getInteger("t") ?: -1,
                    kdfJson.getInteger("mKiB") ?: -1,
                    kdfJson.getInteger("p") ?: -1,
                    unb64(kdfJson.getString("salt"))
                )

                if (kdf.alg != "argon2id" || kdf.t !in 1..MAX_ARGON2_T || kdf.mKiB !in MIN_ARGON2_M_KIB..MAX_ARGON2_M_KIB ||
                    kdf.p !in 1..MAX_ARGON2_P || kdf.salt.size != PanoArcCrypto.SALT_BYTES
                ) {
                    throw PanoArcException(Code.UNSUPPORTED_FORMAT, "Unsupported KDF parameters.")
                }

                kdf
            } else null

            return PanoArcHeader(raw, chunkSize, keyMode, json.getString("keyId"), kdf, noncePrefix, check)
        } catch (e: PanoArcException) {
            throw e
        } catch (e: Exception) {
            throw PanoArcException(Code.UNSUPPORTED_FORMAT, "Invalid header.", e)
        }
    }

    internal fun nonce(prefix: ByteArray, counter: Long) = prefix + u32(counter)

    internal fun aad(header: ByteArray, counter: Long, last: Boolean) =
        header + u32(counter) + byteArrayOf(if (last) 1 else 0)

    internal fun u32(value: Long): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()
    )

    internal fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 8) or (bytes[offset + 3].toLong() and 0xFF)

    /** Reads until [size] bytes or EOF; the result is shorter only at EOF. */
    internal fun readUpTo(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0

        while (read < size) {
            val n = input.read(buffer, read, size - read)

            if (n < 0) {
                break
            }

            read += n
        }

        return if (read == size) buffer else buffer.copyOf(read)
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun unb64(value: String?): ByteArray = Base64.getDecoder().decode(value ?: throw IllegalArgumentException())

    /** Encrypts in chunks; holds one full chunk back until it knows whether it is the last. */
    class EncryptingOutputStream internal constructor(
        output: OutputStream,
        private val header: ByteArray,
        private val noncePrefix: ByteArray,
        encKey: ByteArray,
        private val chunkSize: Int,
        private val sealLast: Boolean
    ) : FilterOutputStream(output) {
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val key = SecretKeySpec(encKey, "AES")
        private val buffer = ByteArray(chunkSize)
        private var filled = 0
        private var counter = 0L
        private var closed = false

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "Stream closed." }

            var offset = off
            var remaining = len

            while (remaining > 0) {
                if (filled == chunkSize) {
                    // More data follows, so the held chunk is not the last one.
                    seal(false)
                }

                val take = minOf(remaining, chunkSize - filled)

                System.arraycopy(b, offset, buffer, filled, take)

                filled += take
                offset += take
                remaining -= take
            }
        }

        override fun flush() {
            out.flush()
        }

        override fun close() {
            if (closed) {
                return
            }

            closed = true

            try {
                seal(sealLast)
                out.flush()
            } finally {
                buffer.fill(0)
                out.close()
            }
        }

        private fun seal(last: Boolean) {
            if (counter > MAX_COUNTER) {
                throw PanoArcException(Code.TOO_LARGE, "The archive has more chunks than the nonce counter allows.")
            }

            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce(noncePrefix, counter)))
            cipher.updateAAD(aad(header, counter, last))

            val sealed = cipher.doFinal(buffer, 0, filled)

            out.write(u32(sealed.size.toLong()))
            out.write(sealed)

            counter++
            filled = 0
        }
    }

    /**
     * Decrypts chunk by chunk. A chunk is the last one exactly when the stream ends right after it,
     * which the lastFlag in its AAD must confirm; a chunk that only authenticates as non-last there
     * means the real final chunk was cut off ([Code.TRUNCATED]).
     */
    class DecryptingInputStream internal constructor(
        private val input: InputStream,
        private val header: PanoArcHeader,
        encKey: ByteArray
    ) : InputStream() {
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val key = SecretKeySpec(encKey, "AES")
        private var plain = ByteArray(0)
        private var position = 0
        private var counter = 0L
        private var nextLength: Long? = null
        private var started = false
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)

            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) {
                return 0
            }

            while (position >= plain.size) {
                if (finished) {
                    return -1
                }

                nextChunk()
            }

            val take = minOf(len, plain.size - position)

            System.arraycopy(plain, position, b, off, take)

            position += take

            return take
        }

        override fun available() = plain.size - position

        override fun close() {
            input.close()
        }

        private fun nextChunk() {
            val length = if (started) nextLength!! else {
                started = true

                readLength() ?: throw PanoArcException(Code.TRUNCATED, "The archive has no chunks.")
            }

            if (length < TAG_BYTES || length > header.chunkSize + TAG_BYTES) {
                throw PanoArcException(Code.TAMPERED, "Invalid chunk length.")
            }

            val sealed = readUpTo(input, length.toInt())

            if (sealed.size < length) {
                throw PanoArcException(Code.TRUNCATED, "The archive ends inside a chunk.")
            }

            val following = readLength()
            val last = following == null

            plain = try {
                open(sealed, last)
            } catch (e: AEADBadTagException) {
                if (last && authenticatesAsNotLast(sealed)) {
                    throw PanoArcException(Code.TRUNCATED, "The archive ends before its final chunk.")
                }

                throw PanoArcException(Code.TAMPERED, "Chunk $counter failed authentication.", e)
            }

            if (!last && plain.size != header.chunkSize) {
                throw PanoArcException(Code.TAMPERED, "A non-final chunk is not full.")
            }

            position = 0
            counter++
            nextLength = following
            finished = last

            if (counter > MAX_COUNTER + 1) {
                throw PanoArcException(Code.TAMPERED, "Too many chunks.")
            }
        }

        private fun open(sealed: ByteArray, last: Boolean): ByteArray {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce(header.noncePrefix, counter)))
            cipher.updateAAD(aad(header.raw, counter, last))

            return cipher.doFinal(sealed)
        }

        private fun authenticatesAsNotLast(sealed: ByteArray) = try {
            open(sealed, false)
            true
        } catch (e: AEADBadTagException) {
            false
        }

        /** The next chunk's length, or null at a clean end of stream. */
        private fun readLength(): Long? {
            val bytes = readUpTo(input, 4)

            return when (bytes.size) {
                0 -> null
                4 -> readU32(bytes, 0)
                else -> throw PanoArcException(Code.TRUNCATED, "The archive ends inside a chunk length.")
            }
        }
    }
}
