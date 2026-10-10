package com.panomc.snapshot

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Per-object sealing of a snapshot repository:
 * `"PSNAP" | 0x01 | u8 keyIdLen | keyId | nonce(12) | AES-256-GCM ct‖tag`, key =
 * HKDF-SHA256(32-byte backup key, "pano-snapshot-v1"), AAD = header ‖ UTF-8(path) — the repo path
 * of a manifest ([SnapshotFormat.manifestPath]) or of a pack index ([SnapshotPack.packPath]), and
 * the chunk name [SnapshotFormat.chunkPath] of a chunk (whichever pack it sits in).
 */
object SnapshotSeal {
    const val VERSION: Byte = 1
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    const val MAX_KEY_ID_BYTES = 64
    const val KEY_BYTES = 32

    private val MAGIC = "PSNAP".toByteArray(Charsets.US_ASCII)
    private val INFO = "pano-snapshot-v1".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    /** The AES key for a 32-byte backup key. */
    fun deriveKey(master: ByteArray): ByteArray {
        require(master.size == KEY_BYTES) { "The backup key must be 32 bytes." }

        return hkdfSha256(master, INFO, KEY_BYTES)
    }

    /** Bytes a sealed object is longer than its plaintext by. */
    fun overhead(keyId: String) = MAGIC.size + 2 + keyId.toByteArray(Charsets.UTF_8).size + NONCE_BYTES + TAG_BYTES

    private fun header(keyId: String): ByteArray {
        val id = keyId.toByteArray(Charsets.UTF_8)

        require(id.size in 1..MAX_KEY_ID_BYTES) { "Unusable key id." }

        return MAGIC + byteArrayOf(VERSION, id.size.toByte()) + id
    }

    /** Seals [plain] for [path] with the derived key [aesKey]; [nonce] only for tests. */
    fun seal(aesKey: ByteArray, keyId: String, path: String, plain: ByteArray, nonce: ByteArray? = null): ByteArray {
        val head = header(keyId)
        val iv = nonce ?: ByteArray(NONCE_BYTES).also { random.nextBytes(it) }

        require(iv.size == NONCE_BYTES) { "The nonce must be 12 bytes." }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(TAG_BYTES * 8, iv))
        cipher.updateAAD(head + path.toByteArray(Charsets.UTF_8))

        val ct = cipher.doFinal(plain)
        val out = ByteArray(head.size + iv.size + ct.size)

        System.arraycopy(head, 0, out, 0, head.size)
        System.arraycopy(iv, 0, out, head.size, iv.size)
        System.arraycopy(ct, 0, out, head.size + iv.size, ct.size)

        return out
    }

    /** The key id in [sealed]'s header, or null when it is not a sealed snapshot object. */
    fun keyIdOf(sealed: ByteArray): String? {
        if (sealed.size < MAGIC.size + 2 || !sealed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) || sealed[MAGIC.size] != VERSION) {
            return null
        }

        val length = sealed[MAGIC.size + 1].toInt() and 0xFF

        if (length !in 1..MAX_KEY_ID_BYTES || sealed.size < MAGIC.size + 2 + length + NONCE_BYTES + TAG_BYTES) {
            return null
        }

        return String(sealed, MAGIC.size + 2, length, Charsets.UTF_8)
    }

    /**
     * Opens [sealed] stored at [path]; [aesKeyFor] maps a key id to its derived key (null = unknown).
     * A foreign header, unknown key, wrong path or tampering throws [SnapshotIntegrityException].
     */
    fun open(aesKeyFor: (String) -> ByteArray?, path: String, sealed: ByteArray): ByteArray {
        val keyId = keyIdOf(sealed) ?: throw SnapshotIntegrityException("SNAPSHOT_OBJECT_INVALID $path")
        val key = aesKeyFor(keyId) ?: throw SnapshotIntegrityException("SNAPSHOT_KEY_UNKNOWN $keyId")
        val headLength = MAGIC.size + 2 + keyId.toByteArray(Charsets.UTF_8).size
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, sealed, headLength, NONCE_BYTES))
        cipher.updateAAD(sealed.copyOfRange(0, headLength) + path.toByteArray(Charsets.UTF_8))

        return try {
            cipher.doFinal(sealed, headLength + NONCE_BYTES, sealed.size - headLength - NONCE_BYTES)
        } catch (_: AEADBadTagException) {
            throw SnapshotIntegrityException("SNAPSHOT_OBJECT_TAMPERED $path")
        }
    }

    /** A key lookup for [open] from `{keyId: 32-byte backup key}`; the AES keys are derived once. */
    fun opener(masterKeys: Map<String, ByteArray>): (String) -> ByteArray? {
        val derived = masterKeys.mapValues { deriveKey(it.value) }

        return { derived[it] }
    }

    /** Seals the stored chunk file bytes of chunk [id] (what [ChunkCodec.encode] returns), bound to [SnapshotFormat.chunkPath]. */
    fun sealChunk(aesKey: ByteArray, keyId: String, id: String, stored: ByteArray): ByteArray =
        seal(aesKey, keyId, SnapshotFormat.chunkPath(id), stored)

    /** Opens a sealed chunk blob of chunk [id] and decodes it with [ChunkCodec.decode]: the raw, verified chunk bytes. */
    fun openChunk(aesKeyFor: (String) -> ByteArray?, id: String, sealed: ByteArray): ByteArray =
        ChunkCodec.decode(id, open(aesKeyFor, SnapshotFormat.chunkPath(id), sealed))

    /** RFC 5869 HKDF-SHA256; an empty [salt] means 32 zero bytes. */
    internal fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int, salt: ByteArray = ByteArray(0)): ByteArray {
        require(length in 1..255 * 32) { "Invalid HKDF length." }

        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1

        while (offset < length) {
            previous = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))

            val take = minOf(previous.size, length - offset)

            System.arraycopy(previous, 0, out, offset, take)

            offset += take
            counter++
        }

        return out
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")

        mac.init(SecretKeySpec(key, "HmacSHA256"))

        return mac.doFinal(data)
    }
}
