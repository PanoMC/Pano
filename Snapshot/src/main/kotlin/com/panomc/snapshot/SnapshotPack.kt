package com.panomc.snapshot

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** One chunk of a pack: its id, where its sealed blob starts in the pack file and its sealed length. */
data class PackEntry(val id: String, val offset: Long, val length: Int)

/**
 * Pack files of a snapshot repository:
 * `"PPACK" | 0x01 | blob* | sealedIndex | u32 BE len(sealedIndex)`, a blob = one sealed chunk (bound to
 * its chunk name, so it does not depend on the pack), `sealedIndex` = the index sealed for the repo path
 * `packs/<packId>`, index = `u32 BE count | count × (32 bytes chunk id | u32 BE offset | u32 BE length)`.
 * Entries are in pack order and contiguous from [HEADER_BYTES] to where the sealed index starts;
 * anything else is a [SnapshotIntegrityException].
 */
object SnapshotPack {
    const val VERSION: Byte = 1
    const val HEADER_BYTES = 6
    const val ENTRY_BYTES = 40
    const val MAX_ENTRIES = 1_000_000
    const val MAX_PACK_BYTES = 1L shl 30

    /** Range of `SnapshotRepoConfig.pack`. */
    const val MIN_TARGET_BYTES = 1L shl 20
    const val MAX_TARGET_BYTES = 256L shl 20

    private const val ID_BYTES = 32
    private const val TRAILER_BYTES = 4

    /** The largest seal overhead (a 64-byte key id). */
    private const val MAX_SEAL_OVERHEAD = 5 + 2 + SnapshotSeal.MAX_KEY_ID_BYTES + SnapshotSeal.NONCE_BYTES + SnapshotSeal.TAG_BYTES
    private const val MAX_SEALED_INDEX = 4L + MAX_ENTRIES.toLong() * ENTRY_BYTES + MAX_SEAL_OVERHEAD

    private val MAGIC = "PPACK".toByteArray(Charsets.US_ASCII)
    private val PACK_ID = Regex("^[0-9a-f]{32}$")

    fun isPackId(id: String) = PACK_ID.matches(id)

    /** The repo path of pack [packId], which its index is sealed for. */
    fun packPath(packId: String): String {
        require(isPackId(packId)) { "Not a pack id." }

        return "packs/$packId"
    }

    fun header(): ByteArray = MAGIC + VERSION

    /** Bytes the index and the trailer of a pack of [count] chunks take at most. */
    fun tailBytes(count: Int) = 4L + count.toLong() * ENTRY_BYTES + MAX_SEAL_OVERHEAD + TRAILER_BYTES

    fun invalid(packId: String, why: String) = SnapshotIntegrityException("SNAPSHOT_PACK_INVALID ${packId.take(32)}: $why")

    /** The index plaintext of [entries] (in pack order). */
    fun encodeIndex(entries: List<PackEntry>): ByteArray {
        require(entries.size in 1..MAX_ENTRIES) { "A pack holds 1 to $MAX_ENTRIES chunks." }

        val out = ByteBuffer.allocate(4 + entries.size * ENTRY_BYTES)

        out.putInt(entries.size)
        entries.forEach { entry ->
            require(SnapshotFormat.isChunkId(entry.id) && entry.offset in HEADER_BYTES..MAX_PACK_BYTES && entry.length > 0) { "Unusable pack entry." }

            for (i in 0 until ID_BYTES) {
                out.put(entry.id.substring(i * 2, i * 2 + 2).toInt(16).toByte())
            }

            out.putInt(entry.offset.toInt())
            out.putInt(entry.length)
        }

        return out.array()
    }

    /**
     * The entries of the index plaintext [index] of pack [packId] whose blobs end at [blobEnd] (where its sealed
     * index starts): count 1…[MAX_ENTRIES], unique ids, contiguous from [HEADER_BYTES] to [blobEnd].
     */
    fun decodeIndex(packId: String, index: ByteArray, blobEnd: Long): List<PackEntry> {
        if (index.size < 4) {
            throw invalid(packId, "the index is cut short")
        }

        val buffer = ByteBuffer.wrap(index)
        val count = Integer.toUnsignedLong(buffer.getInt())

        if (count !in 1..MAX_ENTRIES) {
            throw invalid(packId, "the index holds $count chunks")
        }

        if (index.size.toLong() != 4 + count * ENTRY_BYTES) {
            throw invalid(packId, "the index length does not match its count")
        }

        val entries = ArrayList<PackEntry>(count.toInt())
        val ids = HashSet<String>(count.toInt() * 2)
        val raw = ByteArray(ID_BYTES)
        var next = HEADER_BYTES.toLong()

        repeat(count.toInt()) {
            buffer.get(raw)

            val id = raw.joinToString("") { "%02x".format(it) }
            val offset = Integer.toUnsignedLong(buffer.getInt())
            val length = Integer.toUnsignedLong(buffer.getInt())

            if (!ids.add(id)) {
                throw invalid(packId, "a chunk is listed twice")
            }

            if (offset != next || length < 1 || offset + length > blobEnd) {
                throw invalid(packId, "the index is not contiguous")
            }

            entries += PackEntry(id, offset, length.toInt())
            next = offset + length
        }

        if (next != blobEnd) {
            throw invalid(packId, "the index does not cover the pack")
        }

        return entries
    }

    /**
     * Checks the header and the trailer of the pack file behind [channel] and returns where its sealed index starts
     * (= where its blobs end) and how long it is. Larger than [MAX_PACK_BYTES], a foreign header or a trailer pointing
     * outside the file throws [SnapshotIntegrityException].
     */
    fun indexRange(channel: FileChannel, packId: String): Pair<Long, Int> {
        val size = channel.size()

        if (size > MAX_PACK_BYTES) {
            throw invalid(packId, "larger than $MAX_PACK_BYTES bytes")
        }

        if (size < HEADER_BYTES + TRAILER_BYTES) {
            throw invalid(packId, "cut short")
        }

        if (!read(channel, 0, HEADER_BYTES).contentEquals(header())) {
            throw invalid(packId, "not a pack file")
        }

        val length = Integer.toUnsignedLong(ByteBuffer.wrap(read(channel, size - TRAILER_BYTES, TRAILER_BYTES)).getInt())

        if (length < 1 || length > MAX_SEALED_INDEX || HEADER_BYTES + length + TRAILER_BYTES > size) {
            throw invalid(packId, "the index length is out of range")
        }

        return (size - TRAILER_BYTES - length) to length.toInt()
    }

    /** The checked index of the pack file [file] stored as pack [packId]; [aesKeyFor] as in [SnapshotSeal.open]. */
    fun readIndex(file: File, packId: String, aesKeyFor: (String) -> ByteArray?): List<PackEntry> =
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel -> readIndex(channel, packId, aesKeyFor) }

    /** The checked index of the pack behind [channel]. */
    fun readIndex(channel: FileChannel, packId: String, aesKeyFor: (String) -> ByteArray?): List<PackEntry> {
        val (blobEnd, length) = indexRange(channel, packId)

        return decodeIndex(packId, SnapshotSeal.open(aesKeyFor, packPath(packId), read(channel, blobEnd, length)), blobEnd)
    }

    /** [length] bytes of [channel] from [offset]. */
    fun read(channel: FileChannel, offset: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        val buffer = ByteBuffer.wrap(bytes)

        while (buffer.hasRemaining()) {
            if (channel.read(buffer, offset + buffer.position()) < 0) {
                throw IOException("the pack file ended early")
            }
        }

        return bytes
    }
}

/**
 * Writes one pack file to [file] (a local spool): the header, then every [add]ed sealed chunk, and on [finish] the
 * sealed index and its length. Not thread-safe.
 */
class PackWriter(file: File) : AutoCloseable {
    private val out = FileOutputStream(file).buffered(256 * 1024)
    private val entries = ArrayList<PackEntry>()
    private val ids = HashSet<String>()
    private var position = SnapshotPack.HEADER_BYTES.toLong()

    init {
        out.write(SnapshotPack.header())
    }

    /** Chunks in the pack so far and their sealed bytes. */
    val count: Int
        get() = entries.size

    val chunkBytes: Long
        get() = position - SnapshotPack.HEADER_BYTES

    /** Whether a sealed chunk of [length] bytes still fits (a pack holds at most 1 000 000 chunks and 1 GiB). */
    fun fits(length: Int) = count < SnapshotPack.MAX_ENTRIES && position + length + SnapshotPack.tailBytes(count + 1) <= SnapshotPack.MAX_PACK_BYTES

    /** Appends the sealed chunk [id] (see [SnapshotSeal.sealChunk]); false (and nothing written) when the pack already holds it. */
    fun add(id: String, sealed: ByteArray): Boolean {
        require(sealed.isNotEmpty() && fits(sealed.size)) { "The pack is full." }

        if (!ids.add(id)) {
            return false
        }

        out.write(sealed)
        entries += PackEntry(id, position, sealed.size)
        position += sealed.size

        return true
    }

    /** Seals the index for [packId] and ends the file; returns the pack's entries. */
    fun finish(aesKey: ByteArray, keyId: String, packId: String): List<PackEntry> {
        val sealed = SnapshotSeal.seal(aesKey, keyId, SnapshotPack.packPath(packId), SnapshotPack.encodeIndex(entries))

        out.write(sealed)
        out.write(ByteBuffer.allocate(4).putInt(sealed.size).array())
        out.close()

        return entries
    }

    override fun close() {
        runCatching { out.close() }
    }
}

/**
 * Reads one pack file: the checked index once, then any chunk by id. [aesKeyFor] maps a key id to its derived key
 * ([SnapshotSeal.opener]). Not thread-safe; close it to release the file.
 */
class PackReader(file: File, val packId: String, private val aesKeyFor: (String) -> ByteArray?) : AutoCloseable {
    private val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)

    /** The pack's chunks in pack order, checked. */
    val entries: List<PackEntry> = try {
        SnapshotPack.readIndex(channel, packId, aesKeyFor)
    } catch (exception: Throwable) {
        runCatching { channel.close() }

        throw exception
    }

    private val byId = entries.associateBy { it.id }

    fun has(id: String) = id in byId

    /** The sealed blob of chunk [id], or null when the pack does not hold it. */
    fun sealedChunk(id: String): ByteArray? {
        val entry = byId[id] ?: return null

        return SnapshotPack.read(channel, entry.offset, entry.length)
    }

    /** The raw, verified bytes of chunk [id]: unsealed for its chunk name and decoded. Throws [CorruptChunkException] when the pack does not hold it. */
    fun readChunk(id: String): ByteArray = SnapshotSeal.openChunk(aesKeyFor, id, sealedChunk(id) ?: throw CorruptChunkException(id))

    override fun close() {
        channel.close()
    }
}
